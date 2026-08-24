package com.wmspro.common.external.freighai.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

object FreighAiWarehouseJobContracts {
    const val GENERATION_V1 = "WAREHOUSE_JOB_V1"
    const val SNAPSHOT_V1 = "WAREHOUSE_COMMERCIAL_V1"
    const val SOURCE_WMS = "WMS"
}

/** Exact wire contract of Job service POST /api/v1/warehouse-jobs. */
data class CreateFreighAiWarehouseJobRequest(
    val externalSource: String = FreighAiWarehouseJobContracts.SOURCE_WMS,
    val externalReference: String,
    val generationContractVersion: String = FreighAiWarehouseJobContracts.GENERATION_V1,
    val sourceRevision: Long,
    val sourceContentHash: String,
    val customerSnapshot: WarehouseCustomerSnapshot,
    val warehouseContext: WarehouseContext,
    val commercialSnapshot: WarehouseCommercialSnapshot
)

data class WarehouseCustomerSnapshot(
    val customerId: String,
    val customerName: String,
    val taxRegistrationNumber: String? = null
)

data class WarehouseContext(
    val sourceSystem: String = FreighAiWarehouseJobContracts.SOURCE_WMS,
    val generationContractVersion: String = FreighAiWarehouseJobContracts.GENERATION_V1,
    val sourceTenantId: String,
    val wmsBillingInvoiceId: String,
    val wmsBillingReference: String,
    val billingMonth: String,
    val servicePeriodStart: LocalDate,
    val servicePeriodEnd: LocalDate,
    val projectBucket: String,
    val projectCode: String? = null,
    val projectName: String? = null,
    val warehouseId: String? = null,
    val warehouseName: String? = null,
    val sourceContentHash: String,
    val chargeContentHash: String,
    val calculationVersion: String,
    val dataQuality: String = "COMPLETE",
    val sourceDocumentReferences: List<String> = emptyList()
)

data class WarehouseCommercialSnapshot(
    val snapshotContractVersion: String = FreighAiWarehouseJobContracts.SNAPSHOT_V1,
    val currencyCode: String,
    val frozenAt: Instant,
    val sellingLines: List<WarehouseJobSellingLine>,
    val plannedCostLines: List<WarehouseJobPlannedCostLine>,
    val sellingSubtotal: BigDecimal,
    val taxTotal: BigDecimal,
    val sellingGrandTotal: BigDecimal,
    val plannedCostTotal: BigDecimal,
    val plannedProfit: BigDecimal
)

data class WarehouseJobSellingLine(
    val lineId: String,
    val description: String,
    val serviceCode: String? = null,
    val quantity: BigDecimal,
    val unit: String,
    val unitPrice: BigDecimal,
    val netAmount: BigDecimal,
    val taxPercent: BigDecimal = BigDecimal.ZERO,
    val taxAmount: BigDecimal = BigDecimal.ZERO,
    val grossAmount: BigDecimal,
    val sourceReferences: List<String> = emptyList()
)

data class WarehouseJobPlannedCostLine(
    val costLineId: String,
    val description: String,
    val costCode: String? = null,
    val treatment: String,
    val completionWeight: BigDecimal? = null,
    val quantity: BigDecimal,
    val unit: String,
    val unitCost: BigDecimal,
    val plannedNetAmount: BigDecimal,
    val vendorHint: String? = null,
    val sourceReferences: List<String> = emptyList(),
    val chargeTypeId: String? = null
)

data class ReplaceFreighAiWarehouseSnapshotRequest(
    val expectedVersion: Long,
    val sourceRevision: Long,
    val sourceContentHash: String,
    val commercialSnapshot: WarehouseCommercialSnapshot
)

data class CancelFreighAiWarehouseJobRequest(val expectedVersion: Long, val reason: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiWarehouseJobResponse(
    val jobId: String,
    val jobNo: String,
    val lifecycle: String? = null,
    val externalReference: String? = null,
    val sourceContentHash: String? = null,
    val version: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CreateFreighAiWarehouseJobResult(
    val job: FreighAiWarehouseJobResponse,
    val created: Boolean
)
