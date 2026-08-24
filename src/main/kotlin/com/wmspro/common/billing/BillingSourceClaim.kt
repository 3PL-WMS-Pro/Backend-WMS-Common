package com.wmspro.common.billing

import java.time.Instant

object WarehouseJobGenerationContracts {
    const val V1 = "WAREHOUSE_JOB_V1"
}

enum class BillingSourceClaimState { RESERVED, COMMITTED, RELEASED, MANUAL_REVIEW }
enum class BillingClaimOrphanDecision { COMMIT, RELEASE, MANUAL_REVIEW }

/**
 * Forward-only reservation metadata.  It is nullable on operational records
 * and must be written with Mongo field-level CAS; legacy billingInvoiceId
 * locks are never converted into this shape.
 */
data class BillingSourceClaim(
    val generationContractVersion: String,
    val billingInvoiceId: String,
    val claimOwnerKey: String,
    val sourceLineId: String,
    val accountingPeriod: String,
    val state: BillingSourceClaimState,
    val claimVersion: Long,
    val expiresAt: Instant?,
    val reservedAt: Instant,
    val updatedAt: Instant,
    val lastError: String? = null
)

data class ReserveBillingSourceClaimRequest(
    val billingInvoiceId: String,
    val claimOwnerKey: String,
    val sourceLineId: String,
    val generationContractVersion: String,
    val payloadVersion: Long,
    val accountingPeriod: String,
    val claimVersion: Long,
    val expiresAt: Instant
)

data class TransitionBillingSourceClaimRequest(
    val claimOwnerKey: String,
    val claimVersion: Long,
    val generationContractVersion: String
)
