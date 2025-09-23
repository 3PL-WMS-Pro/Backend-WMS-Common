package com.wmspro.common.jwt

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import java.util.*

@Component
class JwtValidator {
    
    companion object {
        private const val SECRET_KEY = "#FlyBizDigital###LordsOfMarket@2022###LeadToRev@@@2022#"
    }
    
    private val tokenExtractor = JwtTokenExtractor()
    
    fun validateToken(token: String, username: String): Boolean {
        val extractedUsername = tokenExtractor.extractUsername(token)
        return extractedUsername != null && 
               extractedUsername == username && 
               !isTokenExpired(token)
    }
    
    fun validateTokenWithoutExpiry(token: String, username: String): Boolean {
        val extractedUsername = tokenExtractor.extractUsername(token)
        return extractedUsername != null && extractedUsername == username
    }
    
    fun isTokenExpired(token: String): Boolean {
        val expiration = tokenExtractor.extractExpiration(token) ?: return true
        return expiration.before(Date())
    }
    
    fun isTokenValid(token: String): Boolean {
        return try {
            val claims = tokenExtractor.extractAllClaims(token)
            claims != null && !isTokenExpired(token)
        } catch (e: Exception) {
            false
        }
    }
}