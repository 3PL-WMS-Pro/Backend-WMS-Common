package com.wmspro.common.external.freighai.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.external.freighai.dto.ApiEnvelope
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
 * RestTemplate-based client for FreighAI's user endpoints.
 *
 * Auth: same model as FreighAiCustomerClient — caller passes FreighAI JWT.
 * Failure semantics: returns empty map on any failure (logs the cause).
 */
@Component
class FreighAiUserClient(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.freighai.base-url:https://api.freighai.com}")
    private lateinit var baseUrl: String

    /**
     * `POST {baseUrl}/api/v1/users/batch-by-emails` body=List<String>
     * Returns Map<email, fullName>. Missing emails omitted (no row in result).
     */
    fun batchByEmails(emails: List<String>, authToken: String): Map<String, String> {
        if (emails.isEmpty()) return emptyMap()
        val url = "$baseUrl/api/v1/users/batch-by-emails"
        return try {
            val entity = HttpEntity(emails, buildHeaders(authToken))
            val response = restTemplate.exchange(url, HttpMethod.POST, entity, String::class.java)
            if (response.statusCode != HttpStatus.OK) {
                logger.error("FreighAI batch-by-emails returned status {}", response.statusCode)
                return emptyMap()
            }
            val envelope = objectMapper.readValue(
                response.body, object : TypeReference<ApiEnvelope<Map<String, String>>>() {}
            )
            if (!envelope.success || envelope.data == null) {
                logger.warn("FreighAI batch-by-emails returned success=false or null data: {}", envelope.message)
                return emptyMap()
            }
            envelope.data
        } catch (e: RestClientException) {
            logger.error("FreighAI batch-by-emails HTTP error (input size={})", emails.size, e)
            emptyMap()
        } catch (e: Exception) {
            logger.error("FreighAI batch-by-emails unexpected error (input size={})", emails.size, e)
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
