package com.wmspro.common.jwt

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.*

/**
 * Validates and extracts claims from JWTs in non-gateway services.
 *
 * Phase 5 of the leadtorev → FreighAi migration: tokens are now signed with
 * the FreighAi HMAC key (HS256/HS384). The previous implementation hardcoded
 * the leadtorev secret and used `setSigningKey(String)` (which base64-decodes
 * the input) — both wrong for FreighAi tokens, which would parse to null and
 * surface as "Unable to extract username from token" downstream.
 *
 * Mirrors the gateway's [com.wmspro.gateway.jwt.JwtService]: same `jwt.secret`
 * config key, same default, byte[] signing for deterministic key handling.
 */
@Component
class JwtTokenExtractor(
    @Value("\${jwt.secret:freighai-dev-secret-key-256-bits-minimum-for-hs256-algorithm}")
    private val jwtSecret: String
) {

    private val signingKey: ByteArray by lazy { jwtSecret.toByteArray(Charsets.UTF_8) }

    private val objectMapper = ObjectMapper()

    fun extractUsername(token: String): String? {
        val localToken = token.replace("Bearer ", "")
        return extractClaim(localToken) { it.subject }
    }
    
    fun extractUserType(token: String): Long? {
        val localToken = token.replace("Bearer ", "")
        return extractClaim(localToken) { it["userTypeId"] as? Long }
    }
    
    fun extractDepartment(token: String): Long? {
        val localToken = token.replace("Bearer ", "")
        return extractClaim(localToken) { it["departmentId"] as? Long }
    }
    
    fun extractTenantId(token: String): String? {
        val localToken = token.replace("Bearer ", "")
        return extractClaim(localToken) { it["clientId"] as? String }
    }
    
    fun extractJuniors(token: String): Array<String>? {
        val localToken = token.replace("Bearer ", "")
        return extractClaim(localToken) { claims ->
            try {
                val juniors = claims["juniors"] as? String
                when {
                    juniors == null -> null
                    juniors == "[]" -> emptyArray()
                    else -> objectMapper.readValue(juniors, Array<String>::class.java)
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                null
            }
        }
    }
    
    fun extractAccessLevel(token: String): Map<String, String>? {
        val localToken = token.replace("Bearer ", "")
        return extractClaim(localToken) { claims ->
            try {
                val accessLevel = claims["accessLevel"] as? String
                when {
                    accessLevel == null -> null
                    accessLevel == "{}" -> emptyMap()
                    else -> objectMapper.readValue(accessLevel, object : TypeReference<Map<String, String>>() {})
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                null
            }
        }
    }
    
    fun extractExpiration(token: String): Date? {
        return extractClaim(token) { it.expiration }
    }
    
    fun extractAllClaims(token: String): Claims? {
        return try {
            Jwts.parser().setSigningKey(signingKey).parseClaimsJws(token).body
        } catch (e: Exception) {
            println("Token is invalid")
            null
        }
    }
    
    private fun <T> extractClaim(token: String, claimsResolver: (Claims) -> T): T? {
        val claims = extractAllClaims(token)
        return claims?.let { claimsResolver(it) }
    }
}