package com.wmspro.common.jwt

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import java.util.*

@Component
class JwtTokenExtractor {
    
    companion object {
        private const val SECRET_KEY = "#FlyBizDigital###LordsOfMarket@2022###LeadToRev@@@2022#"
    }
    
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
        return extractClaim(localToken) { it["tenantId"] as? String }
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
            Jwts.parser().setSigningKey(SECRET_KEY).parseClaimsJws(token).body
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