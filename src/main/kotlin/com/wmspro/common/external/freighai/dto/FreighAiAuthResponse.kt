package com.wmspro.common.external.freighai.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * FreighAI's `POST /api/v1/auth/login` response shape (the inner `data` of the
 * generic ApiEnvelope; FreighAi's controller wraps it as `ApiResponse<AuthResponse>`).
 *
 * Verified against FreighAi source at services/users-service/.../dto/AuthResponse.kt.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiAuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,                 // seconds
    val tokenType: String = "Bearer",
    val user: FreighAiAuthUserInfo
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FreighAiAuthUserInfo(
    val userId: String,
    val email: String,
    val name: String,
    val role: String,                    // FreighAi UserRole enum value: ADMIN | MANAGER | etc.
    val teams: List<String> = emptyList(),
    val permissions: List<String> = emptyList()
)

/**
 * Login request body sent to FreighAi `POST /api/v1/auth/login`. Mirrors
 * FreighAi's LoginRequest DTO.
 */
data class FreighAiLoginRequest(
    val email: String,
    val password: String
)
