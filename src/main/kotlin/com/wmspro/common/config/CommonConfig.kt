package com.wmspro.common.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import java.time.Duration

@Configuration
class CommonConfig {

    @Bean
    fun restTemplate(builder: RestTemplateBuilder): RestTemplate {
        return builder
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build()
    }

    @Bean
    fun commonObjectMapper(): ObjectMapper {
        return ObjectMapper().apply {
            // Register Kotlin and Java Time modules to support data classes and java.time.* types
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            // Write ISO-8601 strings instead of timestamps for dates
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
}