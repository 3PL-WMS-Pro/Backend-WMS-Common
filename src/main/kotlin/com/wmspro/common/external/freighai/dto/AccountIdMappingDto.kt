package com.wmspro.common.external.freighai.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Wire-format DTO mirroring `Backend-WMS-Tenant-Service`'s
 * `com.wmspro.tenant.dto.AccountIdMappingDto`. Lives in Common because Common's
 * AccountIdMappingClient deserializes Tenant-Service responses into it.
 *
 * Source field values are documented in `AccountIdMapping.kt` (Tenant-Service):
 * leadtorev_match | leadtorev_manual | leadtorev_consolidated | freighai_created | synthetic
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountIdMappingDto(
    val leadtorevId: Long,
    val freighaiCustomerId: String,
    val accountCode: String,
    val source: String,
    val notes: String? = null
)

/**
 * Request body item for `POST /api/v1/internal/account-id-mapping/get-or-assign`.
 * Mirrors Tenant-Service's `GetOrAssignRequestItem`.
 */
data class GetOrAssignItem(
    val freighaiCustomerId: String,
    val accountCode: String
)
