package com.wmspro.common.external.freighai.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.external.freighai.dto.ApiEnvelope
import com.wmspro.common.external.freighai.dto.FreighAiCustomerListItem
import com.wmspro.common.external.freighai.dto.FreighAiCustomerPage
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
 * RestTemplate-based client for FreighAI's customer endpoints.
 *
 * Auth: caller passes the FreighAI JWT (Bearer token) per call. The client
 * adds the "Bearer " prefix if missing — matches the existing AccountService
 * pattern. No `X-Client` header is sent: FreighAI's api-gateway derives tenant
 * from the JWT claim (per D16/D17).
 *
 * On any failure (RestTemplate exception, non-200 status, success=false in
 * envelope), this client logs and returns an empty result. This matches the
 * existing AccountService behaviour and keeps downstream WMS services from
 * cascading failures (they treat missing names/codes as blanks). The Phase 4
 * wiring + parser layer can decide to surface failures more loudly later.
 */
@Component
class FreighAiCustomerClient(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.freighai.base-url:https://api.freighai.com}")
    private lateinit var baseUrl: String

    /**
     * `POST {baseUrl}/api/v1/customers/batch-by-ids` body=List<String>
     * Returns Map<customerId, FreighAiCustomerListItem>. Missing IDs omitted.
     */
    fun batchByIds(customerIds: List<String>, authToken: String): Map<String, FreighAiCustomerListItem> {
        if (customerIds.isEmpty()) return emptyMap()
        return postBatch(customerIds, authToken, "/api/v1/customers/batch-by-ids", "batch-by-ids")
    }

    /**
     * `POST {baseUrl}/api/v1/customers/batch-by-codes` body=List<String>
     * Returns Map<accountCode, FreighAiCustomerListItem>. Missing codes omitted.
     */
    fun batchByCodes(accountCodes: List<String>, authToken: String): Map<String, FreighAiCustomerListItem> {
        if (accountCodes.isEmpty()) return emptyMap()
        return postBatch(accountCodes, authToken, "/api/v1/customers/batch-by-codes", "batch-by-codes")
    }

    /**
     * `GET {baseUrl}/api/v1/customers?page=&size=&search=`
     * Returns the paginated FreighAi customer list (lite shape).
     */
    fun listCustomers(
        page: Int,
        size: Int,
        search: String?,
        authToken: String
    ): FreighAiCustomerPage? {
        val url = UriComponentsBuilder
            .fromUriString("$baseUrl/api/v1/customers")
            .queryParam("page", page)
            .queryParam("size", size)
            .apply { if (!search.isNullOrBlank()) queryParam("search", search) }
            .build()
            .toUriString()

        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity<Void>(buildHeaders(authToken)), String::class.java
            )
            if (response.statusCode != HttpStatus.OK) {
                logger.error("FreighAI listCustomers returned status {}", response.statusCode)
                return null
            }
            val envelope = objectMapper.readValue(
                response.body, object : TypeReference<ApiEnvelope<FreighAiCustomerPage>>() {}
            )
            if (!envelope.success) {
                logger.warn("FreighAI listCustomers returned success=false: {}", envelope.message)
                return null
            }
            envelope.data
        } catch (e: RestClientException) {
            logger.error("FreighAI listCustomers HTTP error", e)
            null
        } catch (e: Exception) {
            logger.error("FreighAI listCustomers unexpected error", e)
            null
        }
    }

    private fun postBatch(
        body: List<String>,
        authToken: String,
        path: String,
        opLabel: String
    ): Map<String, FreighAiCustomerListItem> {
        val url = "$baseUrl$path"
        return try {
            val entity = HttpEntity(body, buildHeaders(authToken))
            val response = restTemplate.exchange(url, HttpMethod.POST, entity, String::class.java)
            if (response.statusCode != HttpStatus.OK) {
                logger.error("FreighAI {} returned status {}", opLabel, response.statusCode)
                return emptyMap()
            }
            val envelope = objectMapper.readValue(
                response.body,
                object : TypeReference<ApiEnvelope<Map<String, FreighAiCustomerListItem>>>() {}
            )
            if (!envelope.success || envelope.data == null) {
                logger.warn("FreighAI {} returned success=false or null data: {}", opLabel, envelope.message)
                return emptyMap()
            }
            envelope.data
        } catch (e: RestClientException) {
            logger.error("FreighAI {} HTTP error (input size={})", opLabel, body.size, e)
            emptyMap()
        } catch (e: Exception) {
            logger.error("FreighAI {} unexpected error (input size={})", opLabel, body.size, e)
            emptyMap()
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
