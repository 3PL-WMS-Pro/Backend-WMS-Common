package com.wmspro.common.external.freighai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.wmspro.common.external.freighai.dto.CreateFreighAiInvoiceRequest
import com.wmspro.common.external.freighai.dto.CreateFreighAiWarehouseJobRequest
import com.wmspro.common.external.freighai.dto.FreighAiInvoiceContracts
import com.wmspro.common.external.freighai.dto.FreighAiLinkedJob
import com.wmspro.common.external.freighai.dto.WarehouseJobPlannedCostLine
import com.wmspro.common.external.freighai.dto.WarehouseJobSellingLine
import com.wmspro.common.external.freighai.dto.WarehouseCommercialSnapshot
import com.wmspro.common.external.freighai.dto.WarehouseContext
import com.wmspro.common.external.freighai.dto.WarehouseCustomerSnapshot
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Instant

class FreighAiWarehouseContractsTest {
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `legacy invoice request does not materialize generic linkage`() {
        val json = mapper.writeValueAsString(
            CreateFreighAiInvoiceRequest(
                invoiceDate = LocalDate.of(2026, 8, 1),
                partyId = "customer-1",
                currencyId = "AED",
                referenceNo = "legacy-ref",
                lineItems = emptyList()
            )
        )
        assertTrue(json.contains("\"purpose\":\"ADDITIONAL\""))
        assertTrue(json.contains("\"jobLinkContractVersion\":null"))
    }

    @Test
    fun `warehouse invoice serializes only generic linked work`() {
        val json = mapper.writeValueAsString(
            CreateFreighAiInvoiceRequest(
                invoiceDate = LocalDate.of(2026, 8, 1),
                partyId = "customer-1",
                purpose = FreighAiInvoiceContracts.WAREHOUSING,
                jobLinkContractVersion = FreighAiInvoiceContracts.GENERIC_JOB_V1,
                linkedJobs = listOf(FreighAiLinkedJob("job-1", "WAREHOUSE", "WJ-1", "customer-1")),
                sourceSystem = "WMS",
                externalReference = "WMS-1-default-2026-08",
                currencyId = "AED",
                referenceNo = "WMS-1-default-2026-08",
                lineItems = emptyList()
            )
        )
        assertTrue(json.contains("\"jobLinkContractVersion\":\"GENERIC_JOB_V1\""))
        assertTrue(json.contains("\"jobCategory\":\"WAREHOUSE\""))
        assertFalse(json.contains("\"linkedJobOrders\":["))
    }

    @Test
    fun `warehouse job request carries immutable v1 identity and hash`() {
        val request = CreateFreighAiWarehouseJobRequest(
            externalReference = "WMS-1-default-2026-08",
            sourceRevision = 1,
            sourceContentHash = "abc",
            customerSnapshot = WarehouseCustomerSnapshot("1", "Customer"),
            warehouseContext = WarehouseContext(
                sourceTenantId = "tenant-1",
                wmsBillingInvoiceId = "bill-1",
                wmsBillingReference = "WMS-1-default-2026-08",
                billingMonth = "2026-08",
                servicePeriodStart = LocalDate.of(2026, 8, 1),
                servicePeriodEnd = LocalDate.of(2026, 8, 31),
                projectBucket = "DEFAULT",
                sourceContentHash = "abc",
                chargeContentHash = "def",
                calculationVersion = "WMS_COST_V1"
            ),
            commercialSnapshot = WarehouseCommercialSnapshot(
                currencyCode = "AED",
                frozenAt = Instant.parse("2026-08-31T00:00:00Z"),
                sellingLines = listOf(
                    WarehouseJobSellingLine("sell-1", "Storage", "charge-1", BigDecimal.ONE, "EA", BigDecimal.TEN, BigDecimal.TEN, grossAmount = BigDecimal.TEN)
                ),
                plannedCostLines = listOf(
                    WarehouseJobPlannedCostLine("cost-1", "Storage", "STORAGE", "INTERNAL_STANDARD", BigDecimal.ONE, BigDecimal.ONE, "EA", BigDecimal.ONE, BigDecimal.ONE)
                ),
                sellingSubtotal = BigDecimal.TEN,
                taxTotal = BigDecimal.ZERO,
                sellingGrandTotal = BigDecimal.TEN,
                plannedCostTotal = BigDecimal.ONE,
                plannedProfit = BigDecimal("9")
            )
        )
        val json = mapper.writeValueAsString(request)
        assertTrue(json.contains("\"generationContractVersion\":\"WAREHOUSE_JOB_V1\""))
        assertTrue(json.contains("\"sourceContentHash\":\"abc\""))
    }
}
