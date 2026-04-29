package com.wmspro.common.external.freighai.parser

import com.wmspro.common.external.freighai.dto.AccountIdMappingDto
import com.wmspro.common.external.freighai.dto.FreighAiCustomerListItem
import com.wmspro.common.service.AccountService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Pure translator: FreighAI customer DTOs → WMS-shaped AccountService responses.
 *
 * Each method takes:
 *   - the original WMS-side input (Longs / Codes the caller asked about)
 *   - the resolved mapping (leadtorev ↔ FreighAI ID lookups)
 *   - the FreighAI customer details
 *
 * and emits the WMS DTO with the same shape leadtorev used to return. Entries
 * that can't be fully resolved (e.g. mapping missing, FreighAI customer not
 * found) are silently omitted from the result map — matches existing
 * AccountService behaviour where downstream services treat blanks as
 * "unknown".
 *
 * `success` is always true on the envelope: partial success is still "success".
 * Hard failures (HTTP errors) are handled at the client layer and surface as
 * empty inputs to the parser.
 */
@Component
class FreighAiAccountParser {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Build AccountNamesResponse from a FreighAI batch-by-ids result.
     *
     * @param accountIds         original input from the caller (preserves which
     *                           IDs were requested — for completeness logging)
     * @param mappingByLeadtorev result of AccountIdMappingClient.batchGetByLeadtorevIds
     *                           (subset of accountIds that have known mappings)
     * @param freighAiByFreighaiId result of FreighAiCustomerClient.batchByIds
     *                           (subset of freighai IDs that exist in FreighAI)
     */
    fun toAccountNamesResponse(
        accountIds: List<Long>,
        mappingByLeadtorev: Map<Long, AccountIdMappingDto>,
        freighAiByFreighaiId: Map<String, FreighAiCustomerListItem>
    ): AccountService.AccountNamesResponse {
        val data = buildLeadtorevKeyedMap(accountIds, mappingByLeadtorev, freighAiByFreighaiId) { it.name }
        if (data.size < accountIds.size) {
            logger.debug("Resolved {} of {} requested account names", data.size, accountIds.size)
        }
        return AccountService.AccountNamesResponse(success = true, message = null, data = data)
    }

    /**
     * Build AccountCodesResponse using FreighAI's `accountCode` field. Per
     * Phase 1 backfill, this equals the leadtorev code for matched customers.
     */
    fun toAccountCodesResponse(
        accountIds: List<Long>,
        mappingByLeadtorev: Map<Long, AccountIdMappingDto>,
        freighAiByFreighaiId: Map<String, FreighAiCustomerListItem>
    ): AccountService.AccountCodesResponse {
        val data = buildLeadtorevKeyedMap(accountIds, mappingByLeadtorev, freighAiByFreighaiId) {
            it.accountCode ?: ""
        }.filterValues { it.isNotEmpty() }
        if (data.size < accountIds.size) {
            logger.debug("Resolved {} of {} requested account codes", data.size, accountIds.size)
        }
        return AccountService.AccountCodesResponse(success = true, message = null, data = data)
    }

    /**
     * Build AccountIdsByCodesResponse: code → leadtorev Long.
     *
     * @param accountCodes         original input from the caller
     * @param freighAiByCode       result of FreighAiCustomerClient.batchByCodes
     * @param leadtorevByFreighaiId result of AccountIdMappingClient.batchGetByFreighaiIds
     *                             (subset of freighai IDs that have a leadtorev Long)
     */
    fun toAccountIdsByCodesResponse(
        accountCodes: List<String>,
        freighAiByCode: Map<String, FreighAiCustomerListItem>,
        leadtorevByFreighaiId: Map<String, Long>
    ): AccountService.AccountIdsByCodesResponse {
        val data = mutableMapOf<String, Long>()
        for (code in accountCodes) {
            val customer = freighAiByCode[code] ?: continue
            val leadtorevId = leadtorevByFreighaiId[customer.customerId] ?: continue
            data[code] = leadtorevId
        }
        if (data.size < accountCodes.size) {
            logger.debug("Resolved {} of {} requested account IDs by codes", data.size, accountCodes.size)
        }
        return AccountService.AccountIdsByCodesResponse(success = true, message = null, data = data)
    }

    /**
     * Shared traversal: for each leadtorev ID, follow the chain
     *   leadtorevId → mapping.freighaiCustomerId → FreighAi customer → field
     * Omits IDs whose chain breaks at any step.
     */
    private inline fun buildLeadtorevKeyedMap(
        accountIds: List<Long>,
        mappingByLeadtorev: Map<Long, AccountIdMappingDto>,
        freighAiByFreighaiId: Map<String, FreighAiCustomerListItem>,
        valueExtractor: (FreighAiCustomerListItem) -> String
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (id in accountIds) {
            val mapping = mappingByLeadtorev[id] ?: continue
            val customer = freighAiByFreighaiId[mapping.freighaiCustomerId] ?: continue
            result[id.toString()] = valueExtractor(customer)
        }
        return result
    }
}
