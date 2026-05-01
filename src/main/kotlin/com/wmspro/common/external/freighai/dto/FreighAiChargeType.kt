package com.wmspro.common.external.freighai.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal

/**
 * Lite representation of a FreighAi ChargeType — enough for the WMS billing
 * domain to (a) populate the admin Service-Catalog ChargeType dropdown and
 * (b) cache the canonical chargeTypeId → label/vatPercent mapping at config
 * time.
 *
 * FreighAi master-data-service exposes `GET /api/v1/charge-types` returning
 * an `ApiEnvelope<List<...>>`. Fields not consumed by WMS are deserialised
 * leniently (`fail-on-unknown-properties` is off project-wide, but this
 * annotation is defensive).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiChargeType(
    val chargeTypeId: String,
    val label: String,
    val vatPercent: BigDecimal,
    val isActive: Boolean = true,
    val isInternal: Boolean = false,
    val specialItemTag: String? = null
)
