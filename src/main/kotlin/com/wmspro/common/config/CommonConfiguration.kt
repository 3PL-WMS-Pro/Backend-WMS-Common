package com.wmspro.common.config

import com.wmspro.common.tenant.TenantInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class CommonConfiguration(
    private val tenantInterceptor: TenantInterceptor
) : WebMvcConfigurer {
    
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(tenantInterceptor)
    }
}