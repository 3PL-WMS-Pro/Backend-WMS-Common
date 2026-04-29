package com.wmspro.common.external.freighai.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Generic envelope returned by every FreighAI API endpoint.
 *
 * Shape (verified by live probe 2026-04-29):
 * ```
 * { "success": true, "message": "...", "data": <T>, "timestamp": "ISO8601" }
 * ```
 *
 * `timestamp` is always present in real responses but treated as optional for
 * forward-compatibility. `data` may be null when `success == false` or when the
 * endpoint legitimately returns nothing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiEnvelope<T>(
    val success: Boolean,
    val message: String? = null,
    val data: T? = null,
    val timestamp: String? = null
)
