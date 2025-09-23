package com.wmspro.common.tenant

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

@Component
class TenantInterceptor : HandlerInterceptor {
    
    companion object {
        private const val TENANT_HEADER = "X-Tenant-Id"
    }
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val tenantId = request.getHeader(TENANT_HEADER)
        
        if (tenantId != null && tenantId.isNotBlank()) {
            TenantContext.setCurrentTenant(tenantId)
        }
        
        return true
    }
    
    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?
    ) {
        TenantContext.clear()
    }
    
    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        TenantContext.clear()
    }
}