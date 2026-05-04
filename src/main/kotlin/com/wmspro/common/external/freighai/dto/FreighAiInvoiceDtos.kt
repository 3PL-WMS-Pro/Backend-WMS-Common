package com.wmspro.common.external.freighai.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal
import java.time.LocalDate

/**
 * FreighAi invoice request/response DTOs — minimal subset the WMS billing
 * module needs. Field names mirror FreighAi's `CreateInvoiceRequest` /
 * `Invoice` shapes exactly so Jackson serialises straight through.
 *
 * Phase 5 uses: `CreateInvoiceRequest`, `FreighAiInvoiceResponse` (for the
 * post-create binding), `findByReferenceNo` (for idempotency lookup), and
 * `cancelInvoice`.
 *
 * Phase 7/8 will add detail-fetch fields (allocations, voucher info, PDF).
 */

/**
 * `POST {freighai}/api/v1/invoices` request body.
 *
 * Notes:
 *   - `linkedJobOrders` is null — WMS invoices are not JO-bound.
 *   - `purpose` is "ADDITIONAL" — bypasses the (jobOrderId, purpose)
 *     uniqueness constraint that FreighAi normally enforces, and is the
 *     correct semantics for a non-JO recurring charge.
 */
data class CreateFreighAiInvoiceRequest(
    val invoiceType: String = "SALES",
    val invoiceDate: LocalDate,
    val partyId: String,
    val purpose: String = "ADDITIONAL",
    val linkedJobOrders: List<Any>? = null,
    val currencyId: String,
    val referenceNo: String,
    val narration: String? = null,
    val lineItems: List<FreighAiInvoiceLineItem>
)

data class FreighAiInvoiceLineItem(
    val description: String,
    val quantity: BigDecimal,
    val unit: String,
    val unitPrice: BigDecimal,
    val chargeTypeId: String,
    val vatPercent: BigDecimal? = null,
    val vatAmount: BigDecimal? = null
)

/**
 * Response shape for FreighAi's create / fetch / cancel invoice endpoints.
 * FreighAi wraps in their ApiEnvelope — the client code unwraps that.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiInvoiceResponse(
    val invoiceId: String,
    val invoiceNo: String,
    val invoiceType: String? = null,
    val invoiceDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val voucherId: String? = null,
    val voucherNo: String? = null,
    val grandTotal: BigDecimal? = null,
    val currentStatus: String? = null,
    val outstandingAmount: BigDecimal? = null,
    val referenceNo: String? = null
)

/**
 * Used by `findInvoiceByReferenceNo` — FreighAi may return zero or more
 * invoices for a referenceNo. WMS uses unique reference per (customer,
 * month) so at most one is expected; client picks `firstOrNull`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiInvoiceListItem(
    val invoiceId: String,
    val invoiceNo: String,
    val currentStatus: String? = null,
    val voucherId: String? = null,
    val grandTotal: BigDecimal? = null,
    val outstandingAmount: BigDecimal? = null,
    val referenceNo: String? = null
)
