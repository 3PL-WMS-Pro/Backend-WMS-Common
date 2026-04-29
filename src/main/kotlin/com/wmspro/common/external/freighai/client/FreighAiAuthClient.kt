package com.wmspro.common.external.freighai.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.external.freighai.dto.ApiEnvelope
import com.wmspro.common.external.freighai.dto.FreighAiAuthResponse
import com.wmspro.common.external.freighai.dto.FreighAiLoginRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate

/**
 * RestTemplate-based client for FreighAi's auth endpoints.
 *
 * The `/api/v1/auth/login` endpoint is public (no JWT required) — FreighAi's
 * api-gateway exempts `/api/v1/auth` from its JwtAuthFilter. So calls here
 * carry only Content-Type, no Authorization header.
 *
 * On HTTP 401/4xx (bad credentials), returns null and the caller surfaces a
 * generic "Invalid credentials" message to the user — same behavior as the
 * existing leadtorev login flow.
 */
@Component
class FreighAiAuthClient(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.freighai.base-url:https://api.freighai.com}")
    private lateinit var baseUrl: String

    /**
     * `POST {baseUrl}/api/v1/auth/login` body=`{email, password}`
     * Returns the decoded FreighAiAuthResponse on success, null on any failure.
     */
    fun login(email: String, password: String): FreighAiAuthResponse? {
        val url = "$baseUrl/api/v1/auth/login"
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.APPLICATION_JSON)
        }
        val body = FreighAiLoginRequest(email = email, password = password)

        return try {
            val response = restTemplate.exchange(url, HttpMethod.POST, HttpEntity(body, headers), String::class.java)
            if (response.statusCode != HttpStatus.OK) {
                logger.warn("FreighAi login returned status {} for email {}", response.statusCode, email)
                return null
            }
            val envelope = objectMapper.readValue(
                response.body,
                object : TypeReference<ApiEnvelope<FreighAiAuthResponse>>() {}
            )
            if (!envelope.success || envelope.data == null) {
                logger.warn("FreighAi login returned success=false: {}", envelope.message)
                return null
            }
            envelope.data
        } catch (e: HttpClientErrorException) {
            // 401 (bad password), 400 (bad request), etc. — treat all as login failure.
            logger.info("FreighAi login rejected for email {}: status={}", email, e.statusCode)
            null
        } catch (e: RestClientException) {
            logger.error("FreighAi login HTTP error for email {}", email, e)
            null
        } catch (e: Exception) {
            logger.error("FreighAi login unexpected error for email {}", email, e)
            null
        }
    }
}
