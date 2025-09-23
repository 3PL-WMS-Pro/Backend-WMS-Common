package com.wmspro.common.config

import org.springframework.context.annotation.Configuration

/**
 * Common configuration for shared components
 * Note: TenantInterceptor is implemented separately in each microservice
 * to allow service-specific customization
 */
@Configuration
class CommonConfiguration {
    // Common beans and configurations can be added here
    // TenantInterceptor is handled by each microservice individually
}