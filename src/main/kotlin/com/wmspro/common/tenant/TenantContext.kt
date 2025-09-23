package com.wmspro.common.tenant

/**
 * Enhanced TenantContext for managing current tenant across the application
 * Thread-safe storage for tenant information
 */
object TenantContext {
    private val currentTenant = ThreadLocal<String>()
    private val tenantAttributes = ThreadLocal<MutableMap<String, Any>>()

    /**
     * Sets the current tenant ID
     */
    fun setCurrentTenant(tenantId: String) {
        currentTenant.set(tenantId)
    }

    /**
     * Gets the current tenant ID (nullable)
     */
    fun getCurrentTenant(): String? {
        return currentTenant.get()
    }

    /**
     * Gets the current tenant ID or throws exception if not set
     * Useful for operations that require tenant context
     */
    fun requireCurrentTenant(): String {
        return getCurrentTenant()
            ?: throw TenantContextNotSetException("No tenant context has been set for this request")
    }

    /**
     * Checks if tenant context is set
     */
    fun hasTenant(): Boolean {
        return getCurrentTenant() != null
    }

    /**
     * Clears the current tenant context
     * Should be called after request processing
     */
    fun clear() {
        currentTenant.remove()
        tenantAttributes.remove()
    }

    /**
     * Executes a block of code with a specific tenant context
     * Automatically restores previous context after execution
     */
    fun <T> executeWithTenant(tenantId: String, block: () -> T): T {
        val previousTenant = getCurrentTenant()
        val previousAttributes = tenantAttributes.get()
        return try {
            setCurrentTenant(tenantId)
            block()
        } finally {
            if (previousTenant != null) {
                setCurrentTenant(previousTenant)
                previousAttributes?.let { tenantAttributes.set(it) }
            } else {
                clear()
            }
        }
    }

    /**
     * Sets an attribute for the current tenant context
     * Useful for storing additional tenant-specific data
     */
    fun setAttribute(key: String, value: Any) {
        val attributes = tenantAttributes.get() ?: mutableMapOf()
        attributes[key] = value
        tenantAttributes.set(attributes)
    }

    /**
     * Gets an attribute from the current tenant context
     */
    fun getAttribute(key: String): Any? {
        return tenantAttributes.get()?.get(key)
    }

    /**
     * Gets the current tenant as Int (for services using numeric tenant IDs)
     */
    fun getCurrentTenantAsInt(): Int? {
        return getCurrentTenant()?.toIntOrNull()
    }

    /**
     * Gets the current tenant as Int or throws if not set or invalid
     */
    fun requireCurrentTenantAsInt(): Int {
        val tenantId = requireCurrentTenant()
        return tenantId.toIntOrNull()
            ?: throw InvalidTenantIdException("Tenant ID is not a valid integer: $tenantId")
    }
}

/**
 * Exception thrown when tenant context is required but not set
 */
class TenantContextNotSetException(message: String) : RuntimeException(message)

/**
 * Exception thrown when tenant ID format is invalid
 */
class InvalidTenantIdException(message: String) : RuntimeException(message)