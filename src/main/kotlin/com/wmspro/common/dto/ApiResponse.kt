package com.wmspro.common.dto

import java.time.LocalDateTime

data class ApiResponse<T>(
    val success: Boolean,
    val message: String? = null,
    val data: T? = null,
    val timestamp: LocalDateTime = LocalDateTime.now()
) {
    companion object {
        fun <T> success(data: T? = null, message: String = "Operation successful"): ApiResponse<T> {
            return ApiResponse(
                success = true,
                message = message,
                data = data
            )
        }
        
        fun <T> error(message: String, data: T? = null): ApiResponse<T> {
            return ApiResponse(
                success = false,
                message = message,
                data = data
            )
        }
    }
}