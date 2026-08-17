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

    /**
     * `POST {baseUrl}/api/v1/auth/refresh` body=`{refreshToken}`
     *
     * FreighAi ROTATES the refresh token — the response carries a NEW refreshToken
     * alongside the new accessToken, and the presented one is revoked. Callers must
     * persist the new one or the next refresh will fail.
     *
     * Returns null on any failure, including an expired or already-rotated refresh
     * token. The caller's correct response to null is to make the user log in again.
     */
    fun refresh(refreshToken: String): RefreshResult {
        val url = "$baseUrl/api/v1/auth/refresh"
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.APPLICATION_JSON)
        }

        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.POST, HttpEntity(mapOf("refreshToken" to refreshToken), headers), String::class.java
            )
            if (response.statusCode != HttpStatus.OK) {
                logger.warn("FreighAi refresh returned status {}", response.statusCode)
                return RefreshResult.Unavailable("FreighAi returned ${response.statusCode}")
            }
            val envelope = objectMapper.readValue(
                response.body,
                object : TypeReference<ApiEnvelope<FreighAiAuthResponse>>() {}
            )
            if (!envelope.success || envelope.data == null) {
                logger.warn("FreighAi refresh returned success=false: {}", envelope.message)
                return RefreshResult.Rejected
            }
            RefreshResult.Success(envelope.data)
        } catch (e: HttpClientErrorException) {
            // 4xx — expired / revoked / already-rotated token. Expected, not exceptional.
            logger.info("FreighAi refresh rejected: status={}", e.statusCode)
            RefreshResult.Rejected
        } catch (e: RestClientException) {
            // Transport failure or 5xx: we could not ASK whether the token is valid.
            // Distinct from Rejected because the caller turns Rejected into a 401,
            // and a 401 tells the mobile client to end the operator's session. A
            // FreighAi blip must not do that.
            logger.error("FreighAi refresh transport error", e)
            RefreshResult.Unavailable(e.message ?: "transport error")
        } catch (e: Exception) {
            logger.error("FreighAi refresh unexpected error", e)
            RefreshResult.Unavailable(e.message ?: "unexpected error")
        }
    }

    /**
     * `POST {baseUrl}/api/v1/auth/logout` body=`{refreshToken}` — revokes the refresh
     * token server-side.
     *
     * Returns true when FreighAi confirms revocation. A false return is NOT a reason to
     * keep the user logged in: the client should clear local credentials regardless, since
     * the access token is short-lived and unusable once discarded. We surface the boolean
     * only so the caller can log the discrepancy.
     */
    fun logout(refreshToken: String): Boolean {
        val url = "$baseUrl/api/v1/auth/logout"
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.APPLICATION_JSON)
        }

        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.POST, HttpEntity(mapOf("refreshToken" to refreshToken), headers), String::class.java
            )
            response.statusCode.is2xxSuccessful
        } catch (e: HttpClientErrorException) {
            // Already-revoked or unknown token — the desired end state either way.
            logger.info("FreighAi logout rejected: status={}", e.statusCode)
            false
        } catch (e: Exception) {
            logger.error("FreighAi logout error", e)
            false
        }
    }
}

/**
 * Result of [FreighAiAuthClient.refresh].
 *
 * [Rejected] and [Unavailable] are deliberately separate. Rejected means FreighAi
 * answered and said the token is no good — the session is genuinely over. Unavailable
 * means we could not ask. Collapsing them makes a FreighAi outage indistinguishable
 * from a revoked token, and since the caller maps rejection to HTTP 401 — which the
 * mobile client treats as terminal — a brief outage would log every warehouse
 * operator out mid-shift.
 */
sealed class RefreshResult {
    data class Success(val auth: FreighAiAuthResponse) : RefreshResult()
    object Rejected : RefreshResult()
    data class Unavailable(val errorMessage: String) : RefreshResult()
}
