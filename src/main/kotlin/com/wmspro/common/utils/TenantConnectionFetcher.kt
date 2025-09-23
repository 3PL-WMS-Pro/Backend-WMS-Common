package com.wmspro.common.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mashape.unirest.http.Unirest
import com.wmspro.common.constants.GlobalConstants
import com.wmspro.common.dto.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Common utility for fetching tenant database connections
 * Used by all microservices to get tenant-specific database configurations
 * Calls Tenant Service internal API following LTR-Backend pattern
 */
@Component
class TenantConnectionFetcher {

    private val logger = LoggerFactory.getLogger(TenantConnectionFetcher::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Value("\${app.tenant.service.url:http://localhost:6010}")
    private lateinit var tenantServiceUrl: String

    /**
     * Fetches tenant database connection from Tenant Service
     * Checks cache first, then calls internal API if needed
     *
     * @param tenantId The tenant/client ID
     * @return Connection string in format "mongodb://host:port/database" or null if not found
     */
    fun fetchTenantConnection(tenantId: Int): String? {
        // Check cache first
        GlobalConstants.TENANT_DB_CONNECTIONS[tenantId]?.let { cached ->
            logger.debug("Using cached connection for tenant: $tenantId")
            return cached
        }

        // Fetch from Tenant Service
        val connectionString = fetchFromTenantService(tenantId)

        if (connectionString != null) {
            // Cache the connection
            GlobalConstants.TENANT_DB_CONNECTIONS[tenantId] = connectionString
            logger.debug("Cached connection for tenant: $tenantId")
        }

        return connectionString
    }

    /**
     * Fetches tenant database connection via Tenant Service internal API
     * Uses Unirest like LTR-Backend pattern
     */
    private fun fetchFromTenantService(tenantId: Int): String? {
        return try {
            val url = "$tenantServiceUrl/api/v1/tenants/internal/$tenantId/database-connection"
            logger.debug("Fetching database connection from Tenant Service: $url")

            val response = Unirest.get(url).asString()

            if (response.status == 200) {
                // Parse the response
                val apiResponse = objectMapper.readValue(response.body, ApiResponse::class.java)

                if (apiResponse.success && apiResponse.data != null) {
                    // Extract connection details from the response
                    val dataMap = apiResponse.data as Map<*, *>
                    val mongoUrl = dataMap["url"] as String
                    val databaseName = dataMap["databaseName"] as String

                    // Build the full connection string
                    val connectionString = "$mongoUrl/$databaseName"
                    logger.info("Successfully fetched connection for tenant $tenantId")

                    connectionString
                } else {
                    logger.error("Failed response from Tenant Service for tenant $tenantId")
                    null
                }
            } else if (response.status == 404) {
                logger.warn("Tenant $tenantId not found in Tenant Service")
                null
            } else if (response.status == 403) {
                logger.warn("Tenant $tenantId is inactive")
                null
            } else {
                logger.error("HTTP error ${response.status} from Tenant Service for tenant $tenantId")
                null
            }
        } catch (e: Exception) {
            logger.error("Error calling Tenant Service for tenant $tenantId", e)
            null
        }
    }

    /**
     * Clears cached connection for a specific tenant
     * Useful when tenant configuration changes
     */
    fun clearTenantCache(tenantId: Int) {
        GlobalConstants.TENANT_DB_CONNECTIONS.remove(tenantId)
        logger.debug("Cleared cached connection for tenant: $tenantId")
    }

    /**
     * Clears all cached connections
     * Useful for refresh scenarios
     */
    fun clearAllCache() {
        GlobalConstants.TENANT_DB_CONNECTIONS.clear()
        logger.info("Cleared all cached tenant connections")
    }

    /**
     * Fetches S3 configuration for a tenant
     * Used by services that need file storage access
     */
    fun fetchTenantS3Config(tenantId: Int): Map<String, Any>? {
        return try {
            val url = "$tenantServiceUrl/api/v1/tenants/internal/$tenantId/s3-configuration"
            logger.debug("Fetching S3 configuration from Tenant Service: $url")

            val response = Unirest.get(url).asString()

            if (response.status == 200) {
                val apiResponse = objectMapper.readValue(response.body, ApiResponse::class.java)

                if (apiResponse.success && apiResponse.data != null) {
                    val s3Config = apiResponse.data as Map<String, Any>
                    logger.info("Successfully fetched S3 config for tenant $tenantId")
                    s3Config
                } else {
                    logger.error("Failed to get S3 config from Tenant Service for tenant $tenantId")
                    null
                }
            } else {
                logger.error("HTTP error ${response.status} fetching S3 config for tenant $tenantId")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching S3 config for tenant $tenantId", e)
            null
        }
    }

    /**
     * Fetches tenant settings by path
     * Useful for getting specific configuration values
     */
    fun fetchTenantSettings(tenantId: Int, settingsPath: String? = null): Map<String, Any>? {
        return try {
            var url = "$tenantServiceUrl/api/v1/tenants/internal/$tenantId/settings"
            if (!settingsPath.isNullOrBlank()) {
                url += "?settingsPath=$settingsPath"
            }
            logger.debug("Fetching tenant settings from: $url")

            val response = Unirest.get(url).asString()

            if (response.status == 200) {
                val apiResponse = objectMapper.readValue(response.body, ApiResponse::class.java)

                if (apiResponse.success && apiResponse.data != null) {
                    val settings = apiResponse.data as Map<String, Any>
                    logger.debug("Successfully fetched settings for tenant $tenantId")
                    settings
                } else {
                    logger.error("Failed to get settings from Tenant Service for tenant $tenantId")
                    null
                }
            } else {
                logger.error("HTTP error ${response.status} fetching settings for tenant $tenantId")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching settings for tenant $tenantId", e)
            null
        }
    }
}