package com.wmspro.common.external.freighai.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.constants.GlobalConstants
import com.wmspro.common.external.freighai.dto.AccountIdMappingDto
import com.wmspro.common.external.freighai.dto.ApiEnvelope
import com.wmspro.common.external.freighai.dto.GetOrAssignItem
import com.wmspro.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate

/**
 * Thin RestTemplate client for `Backend-WMS-Tenant-Service`'s internal
 * account-id-mapping endpoints (`/api/v1/internal/account-id-mapping/...`).
 *
 * Translates between leadtorev account IDs (Long, used by existing WMS
 * Mongo documents) and FreighAI customer IDs (String).
 *
 * Tenant routing: every call sends the X-Client header (current tenant from
 * TenantContext, or caller-supplied) so Tenant-Service routes the lookup to
 * the correct per-tenant DB (e.g. wms_pro_tenant_199.account_id_mapping).
 *
 * Failure semantics: empty map on any error (logs cause). Mirrors FreighAi*
 * clients in this package and the existing TenantConnectionFetcher.
 */
@Component
class AccountIdMappingClient(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.tenant.service.url:http://localhost:6010}")
    private lateinit var tenantServiceUrl: String

    /**
     * Forward batch lookup: leadtorev IDs → AccountIdMappingDto rows.
     * Missing IDs are omitted from the result.
     */
    fun batchGetByLeadtorevIds(
        leadtorevIds: List<Long>,
        tenantId: String = currentTenantOrThrow()
    ): Map<Long, AccountIdMappingDto> {
        if (leadtorevIds.isEmpty()) return emptyMap()
        val url = "$tenantServiceUrl/api/v1/internal/account-id-mapping/by-leadtorev-ids"
        return callAndDecode(
            url, leadtorevIds, tenantId, "by-leadtorev-ids",
            object : TypeReference<ApiEnvelope<Map<String, AccountIdMappingDto>>>() {}
        )?.mapKeys { it.key.toLong() } ?: emptyMap()
    }

    /**
     * Reverse batch lookup: FreighAI customer IDs → leadtorev IDs.
     * Missing IDs are omitted (not auto-minted; use `getOrAssign` if you need
     * unmapped IDs created on the fly).
     */
    fun batchGetByFreighaiIds(
        freighaiCustomerIds: List<String>,
        tenantId: String = currentTenantOrThrow()
    ): Map<String, Long> {
        if (freighaiCustomerIds.isEmpty()) return emptyMap()
        val url = "$tenantServiceUrl/api/v1/internal/account-id-mapping/by-freighai-ids"
        return callAndDecode(
            url, freighaiCustomerIds, tenantId, "by-freighai-ids",
            object : TypeReference<ApiEnvelope<Map<String, Long>>>() {}
        ) ?: emptyMap()
    }

    /**
     * Batch get-or-assign: returns existing leadtorev Long for each input
     * FreighAI customer; mints a synthetic Long (≥ 1,000,000) for any new
     * ones. Used by Phase 5 wrapper endpoints when WMS encounters a
     * never-before-seen FreighAI customer.
     */
    fun batchGetOrAssign(
        items: List<GetOrAssignItem>,
        tenantId: String = currentTenantOrThrow()
    ): Map<String, Long> {
        if (items.isEmpty()) return emptyMap()
        val url = "$tenantServiceUrl/api/v1/internal/account-id-mapping/get-or-assign"
        return callAndDecode(
            url, items, tenantId, "get-or-assign",
            object : TypeReference<ApiEnvelope<Map<String, Long>>>() {}
        ) ?: emptyMap()
    }

    private fun <REQ, RES> callAndDecode(
        url: String,
        body: REQ,
        tenantId: String,
        opLabel: String,
        typeRef: TypeReference<ApiEnvelope<RES>>
    ): RES? {
        return try {
            val entity = HttpEntity(body, buildHeaders(tenantId))
            val response = restTemplate.exchange(url, HttpMethod.POST, entity, String::class.java)
            if (response.statusCode != HttpStatus.OK) {
                logger.error("Tenant-service {} returned status {}", opLabel, response.statusCode)
                return null
            }
            val envelope = objectMapper.readValue(response.body, typeRef)
            if (!envelope.success) {
                logger.warn("Tenant-service {} returned success=false: {}", opLabel, envelope.message)
                return null
            }
            envelope.data
        } catch (e: RestClientException) {
            logger.error("Tenant-service {} HTTP error", opLabel, e)
            null
        } catch (e: Exception) {
            logger.error("Tenant-service {} unexpected error", opLabel, e)
            null
        }
    }

    private fun buildHeaders(tenantId: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        accept = listOf(MediaType.APPLICATION_JSON)
        set(GlobalConstants.CLIENT_HEADER, tenantId)
    }

    private fun currentTenantOrThrow(): String = TenantContext.getCurrentTenant()
        ?: throw IllegalStateException(
            "AccountIdMappingClient called without a tenant in TenantContext; " +
                "pass tenantId explicitly or ensure the caller is inside a tenant-scoped request"
        )
}
