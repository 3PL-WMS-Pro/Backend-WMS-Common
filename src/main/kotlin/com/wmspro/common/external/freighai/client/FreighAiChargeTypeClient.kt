package com.wmspro.common.external.freighai.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.external.freighai.dto.ApiEnvelope
import com.wmspro.common.external.freighai.dto.FreighAiChargeType
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
import org.springframework.web.util.UriComponentsBuilder

/**
 * RestTemplate-based client for FreighAi's master-data ChargeType endpoint.
 *
 * Mirrors the auth + error-swallowing conventions of [FreighAiCustomerClient]:
 * caller passes the FreighAi JWT per call; "Bearer " prefix added if missing.
 * On any failure (RestTemplate exception, non-200 status, success=false in
 * envelope), returns an empty list — the caller (WMS Service-Catalog admin
 * UI's ChargeType picker) treats an empty list as "FreighAi unreachable, try
 * again" rather than cascading failures.
 *
 * No `X-Tenant-Id` header — FreighAi derives tenant from the JWT claim.
 */
@Component
class FreighAiChargeTypeClient(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.freighai.base-url:https://api.freighai.com}")
    private lateinit var baseUrl: String

    /**
     * `GET {baseUrl}/api/v1/charge-types?activeOnly={true|false}` — returns
     * all charge types for the tenant. `activeOnly=true` filters to entries
     * with `isActive=true`; default=true (caller usually wants the dropdown
     * list, which should hide soft-deleted entries).
     */
    fun listChargeTypes(authToken: String, activeOnly: Boolean = true): List<FreighAiChargeType> {
        val url = UriComponentsBuilder
            .fromUriString("$baseUrl/api/v1/charge-types")
            .queryParam("activeOnly", activeOnly)
            .build()
            .toUriString()

        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity<Void>(buildHeaders(authToken)), String::class.java
            )
            if (response.statusCode != HttpStatus.OK) {
                logger.error("FreighAi listChargeTypes returned status {}", response.statusCode)
                return emptyList()
            }
            val envelope = objectMapper.readValue(
                response.body, object : TypeReference<ApiEnvelope<List<FreighAiChargeType>>>() {}
            )
            if (!envelope.success) {
                logger.warn("FreighAi listChargeTypes returned success=false: {}", envelope.message)
                return emptyList()
            }
            envelope.data ?: emptyList()
        } catch (e: RestClientException) {
            logger.error("FreighAi listChargeTypes HTTP error", e)
            emptyList()
        } catch (e: Exception) {
            logger.error("FreighAi listChargeTypes unexpected error", e)
            emptyList()
        }
    }

    /**
     * `GET {baseUrl}/api/v1/charge-types/{chargeTypeId}` — single ChargeType
     * lookup. Used when the WMS Billing engine resolves VAT for a line item
     * just before submitting an invoice (defensive; lets us warn on a stale
     * binding before the FreighAi POST). Returns null on any failure.
     */
    fun getChargeType(chargeTypeId: String, authToken: String): FreighAiChargeType? {
        val url = "$baseUrl/api/v1/charge-types/$chargeTypeId"
        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity<Void>(buildHeaders(authToken)), String::class.java
            )
            if (response.statusCode != HttpStatus.OK) {
                logger.warn("FreighAi getChargeType {} returned status {}", chargeTypeId, response.statusCode)
                return null
            }
            val envelope = objectMapper.readValue(
                response.body, object : TypeReference<ApiEnvelope<FreighAiChargeType>>() {}
            )
            if (!envelope.success) {
                logger.warn("FreighAi getChargeType {} success=false: {}", chargeTypeId, envelope.message)
                return null
            }
            envelope.data
        } catch (e: Exception) {
            logger.error("FreighAi getChargeType {} error", chargeTypeId, e)
            null
        }
    }

    private fun buildHeaders(authToken: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        accept = listOf(MediaType.APPLICATION_JSON)
        set(
            HttpHeaders.AUTHORIZATION,
            if (authToken.startsWith("Bearer ", ignoreCase = true)) authToken else "Bearer $authToken"
        )
    }
}
