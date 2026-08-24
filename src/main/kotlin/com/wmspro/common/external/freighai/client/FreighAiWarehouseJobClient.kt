package com.wmspro.common.external.freighai.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.wmspro.common.external.freighai.dto.ApiEnvelope
import com.wmspro.common.external.freighai.dto.CreateFreighAiWarehouseJobRequest
import com.wmspro.common.external.freighai.dto.CreateFreighAiWarehouseJobResult
import com.wmspro.common.external.freighai.dto.FreighAiWarehouseJobResponse
import com.wmspro.common.external.freighai.dto.ReplaceFreighAiWarehouseSnapshotRequest
import com.wmspro.common.external.freighai.dto.CancelFreighAiWarehouseJobRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

@Component
class FreighAiWarehouseJobClient(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.freighai.base-url:https://api.freighai.com}")
    private lateinit var baseUrl: String

    fun findByExternalReference(
        sourceSystem: String,
        externalReference: String,
        authToken: String,
        sourceTenantId: String
    ): WarehouseJobLookupResult {
        val encodedReference = UriUtils.encodePathSegment(externalReference, StandardCharsets.UTF_8)
        val url = UriComponentsBuilder.fromUriString("$baseUrl/api/v1/warehouse-jobs/external/$encodedReference")
            .queryParam("externalSource", sourceSystem)
            .queryParam("sourceTenantId", sourceTenantId)
            .build().toUriString()
        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity<Void>(headers(authToken)), String::class.java
            )
            val envelope = objectMapper.readValue(
                response.body,
                object : TypeReference<ApiEnvelope<FreighAiWarehouseJobResponse>>() {}
            )
            if (envelope.success && envelope.data != null) WarehouseJobLookupResult.Found(envelope.data)
            else WarehouseJobLookupResult.Unavailable(envelope.message ?: "Invalid FreighAI response")
        } catch (e: HttpClientErrorException.NotFound) {
            WarehouseJobLookupResult.NotFound
        } catch (e: Exception) {
            logger.error("FreighAI Warehouse Job exact lookup failed for {}", externalReference, e)
            WarehouseJobLookupResult.Unavailable(e.message ?: "Lookup failed")
        }
    }

    fun create(
        request: CreateFreighAiWarehouseJobRequest,
        idempotencyKey: String,
        authToken: String
    ): WarehouseJobMutationResult = mutate(
        url = "$baseUrl/api/v1/warehouse-jobs",
        method = HttpMethod.POST,
        body = request,
        idempotencyKey = idempotencyKey,
        authToken = authToken,
        createResponse = true
    )

    fun get(jobId: String, authToken: String): WarehouseJobDocumentResult =
        readDocument("$baseUrl/api/v1/warehouse-jobs/$jobId", authToken)

    fun getReconciliation(jobId: String, authToken: String): WarehouseJobDocumentResult =
        readDocument("$baseUrl/api/v1/warehouse-jobs/$jobId/reconciliation", authToken)

    private fun readDocument(url: String, authToken: String): WarehouseJobDocumentResult = try {
        val response = restTemplate.exchange(
            url, HttpMethod.GET, HttpEntity<Void>(headers(authToken)), String::class.java
        )
        val root = objectMapper.readTree(response.body)
        val data = root.get("data")
        if (root.get("success")?.asBoolean() == true && data != null && !data.isNull) WarehouseJobDocumentResult.Found(data)
        else WarehouseJobDocumentResult.Unavailable(root.get("message")?.asText() ?: "Invalid FreighAI response")
    } catch (e: HttpClientErrorException.NotFound) {
        WarehouseJobDocumentResult.NotFound
    } catch (e: Exception) {
        logger.error("FreighAI Warehouse Job document read failed url={}", url, e)
        WarehouseJobDocumentResult.Unavailable(e.message ?: "Lookup failed")
    }

    fun replaceSnapshot(
        jobId: String,
        request: ReplaceFreighAiWarehouseSnapshotRequest,
        expectedVersion: Long,
        idempotencyKey: String,
        authToken: String
    ): WarehouseJobMutationResult {
        require(request.expectedVersion == expectedVersion) { "expectedVersion header/body mismatch" }
        val requestHeaders = headers(authToken, idempotencyKey)
        return mutate(
            url = "$baseUrl/api/v1/warehouse-jobs/$jobId/snapshot",
            method = HttpMethod.PUT,
            body = request,
            idempotencyKey = idempotencyKey,
            authToken = authToken,
            explicitHeaders = requestHeaders,
            createResponse = false
        )
    }

    fun cancel(
        jobId: String,
        request: CancelFreighAiWarehouseJobRequest,
        idempotencyKey: String,
        authToken: String
    ): WarehouseJobMutationResult = mutate(
        url = "$baseUrl/api/v1/warehouse-jobs/$jobId/cancel",
        method = HttpMethod.POST,
        body = request,
        idempotencyKey = idempotencyKey,
        authToken = authToken,
        createResponse = false
    )

    private fun mutate(
        url: String,
        method: HttpMethod,
        body: Any,
        idempotencyKey: String,
        authToken: String,
        explicitHeaders: HttpHeaders? = null,
        createResponse: Boolean
    ): WarehouseJobMutationResult = try {
        val response = restTemplate.exchange(
            url,
            method,
            HttpEntity(body, explicitHeaders ?: headers(authToken, idempotencyKey)),
            String::class.java
        )
        if (createResponse) {
            val envelope = objectMapper.readValue(
                response.body,
                object : TypeReference<ApiEnvelope<CreateFreighAiWarehouseJobResult>>() {}
            )
            if (envelope.success && envelope.data != null) WarehouseJobMutationResult.Success(envelope.data.job)
            else WarehouseJobMutationResult.Rejected(envelope.message ?: "Invalid FreighAI response")
        } else {
            val envelope = objectMapper.readValue(
                response.body,
                object : TypeReference<ApiEnvelope<FreighAiWarehouseJobResponse>>() {}
            )
            if (envelope.success && envelope.data != null) WarehouseJobMutationResult.Success(envelope.data)
            else WarehouseJobMutationResult.Rejected(envelope.message ?: "Invalid FreighAI response")
        }
    } catch (e: HttpStatusCodeException) {
        WarehouseJobMutationResult.Rejected(extractMessage(e.responseBodyAsString) ?: e.statusCode.toString())
    } catch (e: RestClientException) {
        WarehouseJobMutationResult.Indeterminate(e.message ?: "Transport failure")
    } catch (e: Exception) {
        WarehouseJobMutationResult.Indeterminate(e.message ?: "Unexpected failure")
    }

    private fun headers(
        authToken: String,
        idempotencyKey: String? = null
    ) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        accept = listOf(MediaType.APPLICATION_JSON)
        set(HttpHeaders.AUTHORIZATION, if (authToken.startsWith("Bearer ", true)) authToken else "Bearer $authToken")
        idempotencyKey?.let { set("Idempotency-Key", it) }
    }

    private fun extractMessage(body: String?): String? = try {
        body?.let { objectMapper.readTree(it).get("message")?.asText() }
    } catch (_: Exception) { null }
}

sealed class WarehouseJobLookupResult {
    data class Found(val job: FreighAiWarehouseJobResponse) : WarehouseJobLookupResult()
    data object NotFound : WarehouseJobLookupResult()
    data class Unavailable(val errorMessage: String) : WarehouseJobLookupResult()
}

sealed class WarehouseJobMutationResult {
    data class Success(val job: FreighAiWarehouseJobResponse) : WarehouseJobMutationResult()
    data class Rejected(val errorMessage: String) : WarehouseJobMutationResult()
    data class Indeterminate(val errorMessage: String) : WarehouseJobMutationResult()
}

sealed class WarehouseJobDocumentResult {
    data class Found(val document: JsonNode) : WarehouseJobDocumentResult()
    data object NotFound : WarehouseJobDocumentResult()
    data class Unavailable(val errorMessage: String) : WarehouseJobDocumentResult()
}
