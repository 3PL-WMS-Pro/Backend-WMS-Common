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
    val vatAmount: BigDecimal? = null,
    /**
     * Denormalised ChargeType label. Optional on create (FreighAi resolves it);
     * echoed back verbatim on update so an edit doesn't blank a label FreighAi
     * had already denormalised onto the line.
     */
    val chargeTypeLabel: String? = null,
    /**
     * Revenue sub-ledger this line posts to. WMS never assigns one on create —
     * FreighAi falls back to the default revenue ledger. On update we echo the
     * fetched value so a WMS-side edit can't silently reroute revenue that was
     * assigned to a sub-ledger inside FreighAi.
     */
    val ledgerId: String? = null
)

/**
 * `PUT {freighai}/api/v1/invoices/{id}` request body.
 *
 * FreighAi's `UpdateInvoiceRequest` treats every field as "null = leave
 * unchanged" (and, for free-text fields, "blank = clear"). WMS only ever
 * rewrites the line items, so every other field is deliberately absent from
 * this DTO — Jackson omits them, FreighAi reads them as null, and the
 * invoice's dates / party / reference survive the edit untouched.
 *
 * FreighAi recomputes `amount`, `vatAmount`, `subtotal`, `totalVatAmount` and
 * `grandTotal` from these lines, and rebuilds the paired voucher, so WMS does
 * not send totals.
 *
 * DRAFT-only on the FreighAi side — see `InvoiceService.update`.
 */
data class UpdateFreighAiInvoiceRequest(
    val lineItems: List<FreighAiInvoiceLineItem>
)

/**
 * A line item as FreighAi returns it (create / fetch / update responses).
 * Distinct from [FreighAiInvoiceLineItem] because the response carries the
 * server-computed `lineNo` and `amount` that the request never sends.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiInvoiceLineItemResponse(
    val lineNo: Int,
    val description: String,
    val quantity: BigDecimal,
    val unit: String,
    val unitPrice: BigDecimal,
    val amount: BigDecimal,
    val chargeTypeId: String? = null,
    val chargeTypeLabel: String? = null,
    val vatPercent: BigDecimal? = null,
    val vatAmount: BigDecimal? = null,
    val ledgerId: String? = null
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
    val referenceNo: String? = null,
    /**
     * Line items as FreighAi currently holds them. FreighAi has always
     * returned these on `GET /invoices/{id}`; WMS simply wasn't deserialising
     * them. The invoice-edit flow needs them as the authoritative starting
     * point, so an edit made directly inside FreighAi is never clobbered by a
     * stale WMS-side copy.
     *
     * Empty on the list endpoint, which returns a lighter projection.
     */
    val lineItems: List<FreighAiInvoiceLineItemResponse> = emptyList(),
    val subtotal: BigDecimal? = null,
    val totalVatAmount: BigDecimal? = null
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
