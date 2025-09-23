package com.wmspro.common.tenant

object TenantContext {
    private val currentTenant = ThreadLocal<String>()
    
    fun setCurrentTenant(tenantId: String) {
        currentTenant.set(tenantId)
    }
    
    fun getCurrentTenant(): String? {
        return currentTenant.get()
    }
    
    fun clear() {
        currentTenant.remove()
    }
}