package com.wmspro.common.constants

import java.util.concurrent.ConcurrentHashMap

/**
 * Global constants for WMS system following LTR-Backend pattern
 * Stores shared constants and caches used across microservices
 */
object GlobalConstants {

    // Header used to pass tenant/client ID between services
    const val TENANT_HEADER = "X-Tenant-Id"
    const val CLIENT_HEADER = "X-Client"  // Alternative header name

    // Cache for tenant database connections to avoid repeated lookups
    // Key: TenantId (Int), Value: MongoDB connection string
    val TENANT_DB_CONNECTIONS = ConcurrentHashMap<Int, String>()

    // Query parameter for tenant ID
    const val TENANT_QUERY_PARAM = "tenantId"

    // Placeholder in connection strings that gets replaced with actual database name
    const val DB_NAME_REPLACEMENT = "{dbName}"

    /**
     * Generates the URL to fetch tenant's MongoDB connection string
     * This would typically call the Tenant Service
     */
    fun generateTenantMongoURL(tenantId: String): String {
        return "http://localhost:6010/api/v1/tenants/$tenantId/mongo-url"
    }
}