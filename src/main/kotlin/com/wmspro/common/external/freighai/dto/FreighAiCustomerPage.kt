package com.wmspro.common.external.freighai.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Pagination wrapper returned by FreighAI's `GET /api/v1/customers`.
 *
 * Shape verified by live probe 2026-04-29. `page` is 1-based.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiCustomerPage(
    val content: List<FreighAiCustomerListItem>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)
