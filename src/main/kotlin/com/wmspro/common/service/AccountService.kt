package com.wmspro.common.service

import com.wmspro.common.external.freighai.client.AccountIdMappingClient
import com.wmspro.common.external.freighai.client.FreighAiCustomerClient
import com.wmspro.common.external.freighai.parser.FreighAiAccountParser
import com.wmspro.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * AccountService — resolves WMS account IDs (Long, leadtorev-derived, persisted on
 * existing WMS warehouse documents) to customer names and codes from the external
 * customer-master provider.
 *
 * Migration history (D2 / D3 / D14 in MIGRATION.md):
 *   - Pre-2026-04-29: HTTP-direct to leadtorev (cloud.leadtorev.com) via RestTemplate.
 *   - Phase 4 onwards: routes through FreighAi via the parser layer.
 *     1. AccountIdMappingClient resolves Long ↔ String IDs (talks to wms-tenant-service).
 *     2. FreighAiCustomerClient hits FreighAi's batch endpoints.
 *     3. FreighAiAccountParser translates FreighAi DTOs → existing WMS shapes.
 *
 * Public method signatures, return types, and @Cacheable keys are unchanged so the
 * 32+ downstream services consuming this class continue to work without code changes.
 */
@Service
class AccountService(
    private val accountIdMappingClient: AccountIdMappingClient,
    private val freighAiCustomerClient: FreighAiCustomerClient,
    private val freighAiAccountParser: FreighAiAccountParser
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class AccountNamesResponse(
        val success: Boolean,
        val message: String?,
        val data: Map<String, String>? // account_id -> account_name
    )

    data class AccountCodesResponse(
        val success: Boolean,
        val message: String?,
        val data: Map<String, String>? // account_id -> account_code
    )

    data class AccountIdsByCodesResponse(
        val success: Boolean,
        val message: String?,
        val data: Map<String, Long>? // account_code -> account_id
    )

    /**
     * Fetches account names for the given account IDs from the external customer-master.
     *
     * @param accountIds  WMS account IDs (leadtorev-derived Longs already on WMS docs)
     * @param authToken   FreighAi JWT (Bearer token) — forwarded from the request
     * @param tenantId    WMS tenant id (e.g. "199") — used by the mapping client to
     *                    select the right per-tenant DB on tenant-service
     * @return Map of accountId (String) → account name. Misses (no mapping or no
     *         FreighAi customer) are silently omitted from the map.
     */
    @Cacheable(value = ["accountNames"], key = "#accountIds.toString() + '_' + #tenantId")
    fun fetchAccountNames(
        accountIds: List<Long>,
        authToken: String,
        tenantId: String = TenantContext.getCurrentTenant() ?: ""
    ): Map<String, String> {

        if (accountIds.isEmpty()) return emptyMap()

        logger.debug("Fetching account names for IDs: {} (tenant {})", accountIds, tenantId)

        return try {
            val mapping = accountIdMappingClient.batchGetByLeadtorevIds(accountIds, tenantId)
            if (mapping.isEmpty()) {
                logger.warn(
                    "No leadtorev→FreighAi mappings found for {} account IDs (tenant {}); returning empty map",
                    accountIds.size, tenantId
                )
                return emptyMap()
            }

            val freighaiIds = mapping.values.map { it.freighaiCustomerId }.distinct()
            val customers = freighAiCustomerClient.batchByIds(freighaiIds, authToken)

            val response = freighAiAccountParser.toAccountNamesResponse(accountIds, mapping, customers)
            response.data ?: emptyMap()
        } catch (e: Exception) {
            logger.error("Unexpected error fetching account names (input size={})", accountIds.size, e)
            emptyMap()
        }
    }

    /**
     * Fetches account codes for the given account IDs from the external customer-master.
     * After Phase 1 backfill, FreighAi customers carry the same accountCode that
     * leadtorev did, so historical printed barcode labels remain valid.
     */
    @Cacheable(value = ["accountCodes"], key = "#accountIds.toString() + '_' + #tenantId")
    fun fetchAccountCodes(
        accountIds: List<Long>,
        authToken: String,
        tenantId: String = TenantContext.getCurrentTenant() ?: ""
    ): Map<String, String> {

        if (accountIds.isEmpty()) return emptyMap()

        logger.debug("Fetching account codes for IDs: {} (tenant {})", accountIds, tenantId)

        return try {
            val mapping = accountIdMappingClient.batchGetByLeadtorevIds(accountIds, tenantId)
            if (mapping.isEmpty()) {
                logger.warn(
                    "No leadtorev→FreighAi mappings found for {} account IDs (tenant {}); returning empty map",
                    accountIds.size, tenantId
                )
                return emptyMap()
            }

            val freighaiIds = mapping.values.map { it.freighaiCustomerId }.distinct()
            val customers = freighAiCustomerClient.batchByIds(freighaiIds, authToken)

            val response = freighAiAccountParser.toAccountCodesResponse(accountIds, mapping, customers)
            response.data ?: emptyMap()
        } catch (e: Exception) {
            logger.error("Unexpected error fetching account codes (input size={})", accountIds.size, e)
            emptyMap()
        }
    }

    /**
     * Reverse lookup: given a list of account codes, return matching leadtorev account IDs.
     */
    fun fetchAccountIdsByCodes(
        accountCodes: List<String>,
        authToken: String,
        tenantId: String = TenantContext.getCurrentTenant() ?: ""
    ): Map<String, Long> {

        if (accountCodes.isEmpty()) return emptyMap()

        logger.debug("Fetching account IDs for codes: {} (tenant {})", accountCodes, tenantId)

        return try {
            val customers = freighAiCustomerClient.batchByCodes(accountCodes, authToken)
            if (customers.isEmpty()) {
                logger.warn(
                    "No FreighAi customers matched {} accountCodes (tenant {}); returning empty map",
                    accountCodes.size, tenantId
                )
                return emptyMap()
            }

            val freighaiIds = customers.values.map { it.customerId }.distinct()
            val leadtorevByFreighaiId = accountIdMappingClient.batchGetByFreighaiIds(freighaiIds, tenantId)

            val response = freighAiAccountParser.toAccountIdsByCodesResponse(
                accountCodes, customers, leadtorevByFreighaiId
            )
            response.data ?: emptyMap()
        } catch (e: Exception) {
            logger.error("Unexpected error fetching account IDs by codes (input size={})", accountCodes.size, e)
            emptyMap()
        }
    }

    /**
     * Convenience helper: enriches a list of arbitrary objects with account names by
     * looking up via accountIdExtractor and writing back via accountNameSetter.
     * Unchanged from previous implementation.
     */
    fun <T> enrichWithAccountNames(
        items: List<T>,
        accountIdExtractor: (T) -> Long?,
        accountNameSetter: (T, String?) -> Unit,
        authToken: String
    ): List<T> {
        val uniqueAccountIds = items.mapNotNull(accountIdExtractor).distinct()

        if (uniqueAccountIds.isEmpty()) return items

        val accountNames = fetchAccountNames(uniqueAccountIds, authToken)

        items.forEach { item ->
            val accountId = accountIdExtractor(item)
            if (accountId != null) {
                accountNameSetter(item, accountNames[accountId.toString()])
            }
        }

        return items
    }
}
