package com.wmspro.common.mongo

/**
 * Simple MongoDB connection storage following LTR-Backend pattern
 * Uses ThreadLocal to store connection strings per request
 * Minimal and clean implementation
 */
object MongoConnectionStorage {
    private val storage = ThreadLocal<String>()

    // Default MongoDB URLs - can be set by each service
    var DEFAULT_DB_URL: String = "mongodb://flybizdigi:FlyBizDigi%40123@cloud.leadtorev.com:27170/wms_pro_tenants?authSource=admin&readPreference=primary"
    var DEFAULT_DB_URL_CENTRAL: String = "mongodb://flybizdigi:FlyBizDigi%40123@cloud.leadtorev.com:27170/wms_pro_tenants?authSource=admin&readPreference=primary"

    /**
     * Gets the current MongoDB connection string
     * @param central If true, returns central DB URL when no connection is set
     * Falls back to appropriate default if not set
     */
    fun getConnection(central: Boolean = false): String {
        val connection = storage.get()
        if (connection != null) return connection
        return if (central) DEFAULT_DB_URL_CENTRAL else DEFAULT_DB_URL
    }

    /**
     * Sets the MongoDB connection string for the current thread
     */
    fun setConnection(connectionString: String) {
        storage.set(connectionString)
    }

    /**
     * Clears the current thread's connection
     */
    fun clear() {
        storage.remove()
    }
}