package com.wmspro.common.dto

data class PageRequest(
    val page: Int = 0,
    val size: Int = 20,
    val sort: String? = null,
    val direction: String = "ASC"
)