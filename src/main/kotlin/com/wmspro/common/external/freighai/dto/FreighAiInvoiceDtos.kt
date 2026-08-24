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
 * Legacy callers retain ADDITIONAL. Warehouse Job callers must explicitly
 * select WAREHOUSING and GENERIC_JOB_V1; they never populate linkedJobOrders.
 */
data class CreateFreighAiInvoiceRequest(
    val invoiceType: String = "SALES",
    val invoiceDate: LocalDate,
    val partyId: String,
    val purpose: String = "ADDITIONAL",
    val linkedJobOrders: List<Any>? = null,
    /**
     * Forward-only generic work linkage.  Legacy callers leave these fields
     * absent; Warehouse Job callers must persist GENERIC_JOB_V1 and use
     * linkedJobs rather than manufacturing a shipment-style linkedJobOrders
     * entry.
     */
    val jobLinkContractVersion: String? = null,
    val linkedJobs: List<FreighAiLinkedJob>? = null,
    val sourceSystem: String? = null,
    val externalReference: String? = null,
    val currencyId: String,
    val referenceNo: String,
    val narration: String? = null,
    val lineItems: List<FreighAiInvoiceLineItem>
)

data class FreighAiLinkedJob(
    val jobId: String,
    val jobCategory: String,
    val jobNo: String? = null,
    val customerId: String? = null
)

object FreighAiInvoiceContracts {
    const val GENERIC_JOB_V1 = "GENERIC_JOB_V1"
    const val WAREHOUSING = "WAREHOUSING"
    const val SOURCE_WMS = "WMS"
}

data class FreighAiInvoiceLineItem(
    val lineId: String? = null,
    val description: String,
    val quantity: BigDecimal,
    val unit: String,
    val unitPrice: BigDecimal,
    val chargeTypeId: String,
    /** Required on GENERIC_JOB_V1 Warehouse SI lines for fail-closed scoped ledger resolution. */
    val warehouseAccountingCategory: String? = null,
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
    val lineItems: List<FreighAiInvoiceLineItem>,
    /** Required by FreighAI only for GENERIC_JOB_V1 optimistic concurrency. */
    val expectedDocumentRevision: Long? = null
)

/**
 * A line item as FreighAi returns it (create / fetch / update responses).
 * Distinct from [FreighAiInvoiceLineItem] because the response carries the
 * server-computed `lineNo` and `amount` that the request never sends.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiInvoiceLineItemResponse(
    val lineNo: Int,
    val lineId: String? = null,
    val description: String,
    val quantity: BigDecimal,
    val unit: String,
    val unitPrice: BigDecimal,
    val amount: BigDecimal,
    val chargeTypeId: String? = null,
    val chargeTypeLabel: String? = null,
    val vatPercent: BigDecimal? = null,
    val vatAmount: BigDecimal? = null,
    val ledgerId: String? = null,
    /** Present on GENERIC_JOB_V1 Warehouse lines; required again on updates. */
    val warehouseAccountingCategory: String? = null
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
    val sourceSystem: String? = null,
    val externalReference: String? = null,
    val jobLinkContractVersion: String? = null,
    val linkedJobs: List<FreighAiLinkedJob> = emptyList(),
    val currencyId: String? = null,
    val currency: FreighAiCurrencyEmbed? = null,
    val documentRevision: Long = 0,
    val allocationRevision: Long = 0,
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiCurrencyEmbed(val code: String, val symbol: String? = null)

data class ReplaceFreighAiJobAllocationsRequest(
    val expectedAllocationRevision: Long,
    val allocations: List<FreighAiJobAllocationItem>
)

data class FreighAiJobAllocationItem(
    val allocationKey: String,
    val invoiceLineId: String,
    val target: String = "JOB",
    val jobId: String,
    val jobCategory: String = "WAREHOUSE",
    val costLineId: String? = null,
    val jobLineId: String? = null,
    val netAmount: BigDecimal,
    val vatAmount: BigDecimal = BigDecimal.ZERO,
    val grossAmount: BigDecimal,
    val documentCurrency: String,
    val baseCurrency: String,
    val postingFxRate: BigDecimal = BigDecimal.ONE,
    val baseNetAmount: BigDecimal,
    val baseVatAmount: BigDecimal = BigDecimal.ZERO,
    val baseGrossAmount: BigDecimal
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReplaceFreighAiJobAllocationsResponse(
    val invoiceId: String,
    val allocationRevision: Long,
    val allocatedGross: BigDecimal,
    val unallocatedGross: BigDecimal
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiJobAllocationResponse(
    val allocationKey: String,
    val invoiceLineId: String,
    val target: String,
    val jobId: String? = null,
    val jobCategory: String? = null,
    val costLineId: String? = null,
    val jobLineId: String? = null,
    val netAmount: BigDecimal,
    val vatAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val documentCurrency: String,
    val baseCurrency: String,
    val postingFxRate: BigDecimal,
    val baseNetAmount: BigDecimal,
    val baseVatAmount: BigDecimal,
    val baseGrossAmount: BigDecimal
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiJobAllocationPage(val content: List<FreighAiJobAllocationResponse> = emptyList())

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
