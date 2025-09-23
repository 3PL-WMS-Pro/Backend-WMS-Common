package com.wmspro.common.schema

/**
 * PermissionsSchema - Shared permission schema for role and user mapping models
 * This schema defines all 17 permission types across operational, management, and system categories
 */
data class PermissionsSchema(
    // Operational Permissions - Inbound (3 permissions)
    val canOffload: Boolean = false,
    val canReceive: Boolean = false, // Includes integrated QC operations
    val canPutaway: Boolean = false,

    // Operational Permissions - Outbound (4 permissions)
    val canPick: Boolean = false,
    val canPackMove: Boolean = false,
    val canPickPackMove: Boolean = false,
    val canLoad: Boolean = false,

    // Operational Permissions - Inventory (3 permissions)
    val canCount: Boolean = false,
    val canTransfer: Boolean = false,
    val canAdjustInventory: Boolean = false,

    // Management Permissions (5 permissions)
    val canViewReports: Boolean = false,
    val canManageUsers: Boolean = false,
    val canManageWarehouses: Boolean = false,
    val canConfigureSettings: Boolean = false,
    val canViewBilling: Boolean = false,

    // System Permissions (2 permissions)
    val canAccessApi: Boolean = true,
    val canUseMobileApp: Boolean = false,
    val canExportData: Boolean = false
) {
    companion object {
        /**
         * Creates permissions for a system administrator with all permissions enabled
         */
        fun createAdminPermissions(): PermissionsSchema {
            return PermissionsSchema(
                // Inbound
                canOffload = true,
                canReceive = true,
                canPutaway = true,
                // Outbound
                canPick = true,
                canPackMove = true,
                canPickPackMove = true,
                canLoad = true,
                // Inventory
                canCount = true,
                canTransfer = true,
                canAdjustInventory = true,
                // Management
                canViewReports = true,
                canManageUsers = true,
                canManageWarehouses = true,
                canConfigureSettings = true,
                canViewBilling = true,
                // System
                canAccessApi = true,
                canUseMobileApp = true,
                canExportData = true
            )
        }

        /**
         * Creates permissions for an operational worker with basic operational permissions
         */
        fun createOperationalPermissions(): PermissionsSchema {
            return PermissionsSchema(
                // Inbound
                canOffload = true,
                canReceive = true,
                canPutaway = true,
                // Outbound
                canPick = true,
                canPackMove = true,
                canPickPackMove = true,
                canLoad = true,
                // Inventory
                canCount = true,
                canTransfer = true,
                canAdjustInventory = false,
                // Management - all false
                canViewReports = false,
                canManageUsers = false,
                canManageWarehouses = false,
                canConfigureSettings = false,
                canViewBilling = false,
                // System
                canAccessApi = true,
                canUseMobileApp = true,
                canExportData = false
            )
        }

        /**
         * Creates permissions for a manager with operational viewing and management permissions
         */
        fun createManagerPermissions(): PermissionsSchema {
            return PermissionsSchema(
                // Inbound - view only
                canOffload = false,
                canReceive = false,
                canPutaway = false,
                // Outbound - view only
                canPick = false,
                canPackMove = false,
                canPickPackMove = false,
                canLoad = false,
                // Inventory
                canCount = false,
                canTransfer = false,
                canAdjustInventory = true,
                // Management
                canViewReports = true,
                canManageUsers = true,
                canManageWarehouses = true,
                canConfigureSettings = false,
                canViewBilling = true,
                // System
                canAccessApi = true,
                canUseMobileApp = true,
                canExportData = true
            )
        }

        /**
         * Creates minimal permissions for read-only users
         */
        fun createReadOnlyPermissions(): PermissionsSchema {
            return PermissionsSchema(
                // All operational permissions false
                canOffload = false,
                canReceive = false,
                canPutaway = false,
                canPick = false,
                canPackMove = false,
                canPickPackMove = false,
                canLoad = false,
                canCount = false,
                canTransfer = false,
                canAdjustInventory = false,
                // Management
                canViewReports = true,
                canManageUsers = false,
                canManageWarehouses = false,
                canConfigureSettings = false,
                canViewBilling = false,
                // System
                canAccessApi = true,
                canUseMobileApp = false,
                canExportData = false
            )
        }
    }

    /**
     * Merges this permission set with another, taking the maximum permission level
     */
    fun mergeWith(other: PermissionsSchema): PermissionsSchema {
        return PermissionsSchema(
            canOffload = this.canOffload || other.canOffload,
            canReceive = this.canReceive || other.canReceive,
            canPutaway = this.canPutaway || other.canPutaway,
            canPick = this.canPick || other.canPick,
            canPackMove = this.canPackMove || other.canPackMove,
            canPickPackMove = this.canPickPackMove || other.canPickPackMove,
            canLoad = this.canLoad || other.canLoad,
            canCount = this.canCount || other.canCount,
            canTransfer = this.canTransfer || other.canTransfer,
            canAdjustInventory = this.canAdjustInventory || other.canAdjustInventory,
            canViewReports = this.canViewReports || other.canViewReports,
            canManageUsers = this.canManageUsers || other.canManageUsers,
            canManageWarehouses = this.canManageWarehouses || other.canManageWarehouses,
            canConfigureSettings = this.canConfigureSettings || other.canConfigureSettings,
            canViewBilling = this.canViewBilling || other.canViewBilling,
            canAccessApi = this.canAccessApi || other.canAccessApi,
            canUseMobileApp = this.canUseMobileApp || other.canUseMobileApp,
            canExportData = this.canExportData || other.canExportData
        )
    }

    /**
     * Checks if user has any operational permission
     */
    fun hasOperationalPermissions(): Boolean {
        return canOffload || canReceive || canPutaway ||
               canPick || canPackMove || canPickPackMove || canLoad ||
               canCount || canTransfer || canAdjustInventory
    }

    /**
     * Checks if user has any management permission
     */
    fun hasManagementPermissions(): Boolean {
        return canViewReports || canManageUsers || canManageWarehouses ||
               canConfigureSettings || canViewBilling
    }

    /**
     * Checks if user has any inbound operation permission
     */
    fun hasInboundPermissions(): Boolean {
        return canOffload || canReceive || canPutaway
    }

    /**
     * Checks if user has any outbound operation permission
     */
    fun hasOutboundPermissions(): Boolean {
        return canPick || canPackMove || canPickPackMove || canLoad
    }

    /**
     * Checks if user has any inventory operation permission
     */
    fun hasInventoryPermissions(): Boolean {
        return canCount || canTransfer || canAdjustInventory
    }
}