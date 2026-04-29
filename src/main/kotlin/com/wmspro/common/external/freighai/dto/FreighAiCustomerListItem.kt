package com.wmspro.common.external.freighai.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Lightweight customer summary returned by FreighAI's list endpoints
 * (`GET /api/v1/customers`, `POST /api/v1/customers/batch-by-ids`,
 * `POST /api/v1/customers/batch-by-codes`).
 *
 * Shape verified by live probe 2026-04-29 against `freighai_testco`. Fields
 * marked nullable below are observed-nullable in production.
 *
 * NOTE: `accountCode` is nullable in the entity (Phase 1 design — for
 * backward compatibility with pre-migration documents) but is populated for
 * every customer post-Phase-1 backfill. Treat as effectively non-null at
 * runtime, but keep nullable here to match the source-of-truth contract.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiCustomerListItem(
    val customerId: String,
    val accountCode: String?,
    val name: String,
    val type: String,                 // CustomerType enum value (DIRECT_CUSTOMER | FREIGHT_FORWARDER)
    val tier: String,                 // CustomerTier enum value (BRONZE | SILVER | GOLD | PLATINUM)
    val status: String,               // CustomerStatus enum value (ACTIVE | INACTIVE | ...)
    val primaryContactName: String?,
    val primaryContactEmail: String?,
    val industry: String?,
    val accountOwnerId: String,
    val accountOwnerName: String?,
    val totalContacts: Int,
    val createdAt: String,            // ISO8601 timestamp
    val updatedAt: String             // ISO8601 timestamp
)
