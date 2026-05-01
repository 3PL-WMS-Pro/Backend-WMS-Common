package com.wmspro.common.external.freighai.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.external.freighai.dto.ApiEnvelope
import com.wmspro.common.external.freighai.dto.CreateFreighAiInvoiceRequest
import com.wmspro.common.external.freighai.dto.FreighAiInvoiceListItem
import com.wmspro.common.external.freighai.dto.FreighAiInvoiceResponse
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
 * RestTemplate-based client for FreighAi's invoice endpoints. Mirrors the
 * conventions of [FreighAiCustomerClient] and [FreighAiChargeTypeClient]:
 * caller passes the FreighAi JWT per call; failures degrade to null/empty
 * with structured logging rather than throwing.
 *
 * Caller pattern: WMS Tenant Service's BillingRunService obtains a JWT
 * from a service-account login (or forwards an admin's JWT for
 * admin-triggered runs) and threads it through.
 *
 * Phase 5 uses: `findByReferenceNo` (idempotency check), `createInvoice`,
 * `getInvoice` (for status sync), `cancelInvoice`.
 *
 * Phase 7/8 will add: `sendInvoice`, `getEmailDraft`, `sendEmail`,
 * `markPaid`, `getPdfBytes`.
 */
@Component
class FreighAiInvoiceClient(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.freighai.base-url:https://api.freighai.com}")
    private lateinit var baseUrl: String

    /**
     * Idempotency lookup. WMS sets `referenceNo = "WMS-{customerId}-{month}"`
     * deterministically; before any create attempt the billing engine queries
     * this method first. If a previous attempt succeeded but the WMS-side
     * persist failed, we adopt the existing invoice instead of duplicating.
     *
     * FreighAi's invoices list endpoint supports a `search` param that
     * matches against invoiceNo and referenceNo — using it to find the row
     * by referenceNo. Returns `firstOrNull` because WMS guarantees uniqueness
     * via its own (customerId, billingMonth) index.
     */
    fun findInvoiceByReferenceNo(referenceNo: String, authToken: String): FreighAiInvoiceListItem? {
        val url = UriComponentsBuilder
            .fromUriString("$baseUrl/api/v1/invoices")
            .queryParam("search", referenceNo)
            .queryParam("size", 5)
            .build()
            .toUriString()
        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity<Void>(buildHeaders(authToken)), String::class.java
            )
            if (response.statusCode != HttpStatus.OK) {
                logger.warn("FreighAi findInvoiceByReferenceNo {} returned status {}", referenceNo, response.statusCode)
                return null
            }
            val envelope = objectMapper.readValue(
                response.body,
                object : TypeReference<ApiEnvelope<FreighAiInvoiceListPage>>() {}
            )
            if (!envelope.success) {
                logger.warn("FreighAi findInvoiceByReferenceNo {} success=false: {}", referenceNo, envelope.message)
                return null
            }
            envelope.data?.content?.firstOrNull { it.referenceNo == referenceNo }
        } catch (e: Exception) {
            logger.error("FreighAi findInvoiceByReferenceNo {} error", referenceNo, e)
            null
        }
    }

    /**
     * Create a Sales Invoice in FreighAi. Called only AFTER a referenceNo
     * lookup confirms no duplicate exists (or to recover from a missing
     * binding). FreighAi creates the paired Voucher atomically and returns
     * the IDs in `data`.
     */
    fun createInvoice(
        request: CreateFreighAiInvoiceRequest,
        authToken: String
    ): InvoiceCreationResult {
        return try {
            val response = restTemplate.exchange(
                "$baseUrl/api/v1/invoices",
                HttpMethod.POST,
                HttpEntity(request, buildHeaders(authToken)),
                String::class.java
            )
            val envelope = objectMapper.readValue(
                response.body,
                object : TypeReference<ApiEnvelope<FreighAiInvoiceResponse>>() {}
            )
            if (!envelope.success || envelope.data == null) {
                InvoiceCreationResult.Failure(envelope.message ?: "FreighAi returned success=false with no data")
            } else {
                InvoiceCreationResult.Success(envelope.data)
            }
        } catch (e: RestClientException) {
            logger.error("FreighAi createInvoice transport error (refNo={})", request.referenceNo, e)
            InvoiceCreationResult.Failure("Transport error: ${e.message}")
        } catch (e: Exception) {
            logger.error("FreighAi createInvoice unexpected error (refNo={})", request.referenceNo, e)
            InvoiceCreationResult.Failure("Unexpected error: ${e.message}")
        }
    }

    fun getInvoice(invoiceId: String, authToken: String): FreighAiInvoiceResponse? {
        val url = "$baseUrl/api/v1/invoices/$invoiceId"
        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity<Void>(buildHeaders(authToken)), String::class.java
            )
            if (response.statusCode != HttpStatus.OK) {
                logger.warn("FreighAi getInvoice {} status {}", invoiceId, response.statusCode)
                return null
            }
            val envelope = objectMapper.readValue(
                response.body,
                object : TypeReference<ApiEnvelope<FreighAiInvoiceResponse>>() {}
            )
            if (!envelope.success) null else envelope.data
        } catch (e: Exception) {
            logger.error("FreighAi getInvoice {} error", invoiceId, e)
            null
        }
    }

    /**
     * Cancel the invoice in FreighAi. Used by the WMS billing engine's
     * cancel flow before unlocking GRN/GIN/ServiceLog rows.
     *
     * Returns `true` on success or if the invoice is already cancelled
     * (idempotent). Returns `false` on any other failure — caller decides
     * whether to retry or escalate.
     */
    fun cancelInvoice(invoiceId: String, reason: String, authToken: String): Boolean {
        val url = "$baseUrl/api/v1/invoices/$invoiceId/cancel"
        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.POST,
                HttpEntity(mapOf("reason" to reason), buildHeaders(authToken)),
                String::class.java
            )
            response.statusCode.is2xxSuccessful
        } catch (e: Exception) {
            logger.error("FreighAi cancelInvoice {} error", invoiceId, e)
            false
        }
    }

    /**
     * Phase 8: send the invoice (DRAFT → SENT). FreighAi auto-posts the
     * paired voucher and triggers the customer email render.
     */
    fun sendInvoice(invoiceId: String, remarks: String?, authToken: String): SendResult {
        val url = "$baseUrl/api/v1/invoices/$invoiceId/send"
        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.POST,
                HttpEntity(mapOf("remarks" to remarks).filterValues { it != null }, buildHeaders(authToken)),
                String::class.java
            )
            if (response.statusCode.is2xxSuccessful) SendResult.Success
            else SendResult.Failure("FreighAi returned ${response.statusCode}")
        } catch (e: Exception) {
            logger.error("FreighAi sendInvoice {} error", invoiceId, e)
            SendResult.Failure(e.message ?: "transport error")
        }
    }

    /** Phase 8: fetch the email-draft body (recipients, subject, html). */
    fun getEmailDraft(invoiceId: String, authToken: String): String? {
        val url = "$baseUrl/api/v1/invoices/$invoiceId/email-draft"
        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity<Void>(buildHeaders(authToken)), String::class.java
            )
            if (response.statusCode.is2xxSuccessful) response.body else null
        } catch (e: Exception) {
            logger.error("FreighAi getEmailDraft {} error", invoiceId, e)
            null
        }
    }

    /** Phase 8: send invoice email — body shape matches FreighAi's SendInvoiceEmailRequest. */
    fun sendEmail(invoiceId: String, body: Map<String, Any?>, authToken: String): Boolean {
        val url = "$baseUrl/api/v1/invoices/$invoiceId/email"
        return try {
            val response = restTemplate.exchange(
                url, HttpMethod.POST,
                HttpEntity(body, buildHeaders(authToken)),
                String::class.java
            )
            response.statusCode.is2xxSuccessful
        } catch (e: Exception) {
            logger.error("FreighAi sendEmail {} error", invoiceId, e)
            false
        }
    }

    /** Phase 8: stream PDF bytes. Caller writes them to the HTTP response. */
    fun getInvoicePdf(invoiceId: String, authToken: String): ByteArray? {
        val url = "$baseUrl/api/v1/invoices/$invoiceId/pdf"
        return try {
            val headers = buildHeaders(authToken).apply {
                accept = listOf(MediaType.APPLICATION_PDF, MediaType.APPLICATION_OCTET_STREAM)
            }
            val response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity<Void>(headers), ByteArray::class.java
            )
            if (response.statusCode.is2xxSuccessful) response.body else null
        } catch (e: Exception) {
            logger.error("FreighAi getInvoicePdf {} error", invoiceId, e)
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

/**
 * Result of [FreighAiInvoiceClient.createInvoice]. Sealed so callers can
 * pattern-match success vs failure without nullable juggling.
 */
sealed class InvoiceCreationResult {
    data class Success(val invoice: FreighAiInvoiceResponse) : InvoiceCreationResult()
    data class Failure(val errorMessage: String) : InvoiceCreationResult()
}

/** Result of [FreighAiInvoiceClient.sendInvoice]. */
sealed class SendResult {
    object Success : SendResult()
    data class Failure(val errorMessage: String) : SendResult()
}

/**
 * Local shape for the paged invoice list. FreighAi's actual response wraps
 * with extra metadata; we only need `content`.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiInvoiceListPage(
    val content: List<FreighAiInvoiceListItem> = emptyList()
)
