package com.wmspro.common.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.RestClientException

@Service
class UserService(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.user-service.url:https://cloud.leadtorev.com/users/get/email-to-fullname-mapping}")
    private lateinit var userServiceUrl: String

    data class UserFullNameRequest(
        val emails: List<String>
    )

    data class UserFullNameResponse(
        val success: Boolean,
        val message: String?,
        val data: Map<String, String>? // email -> fullName
    )

    /**
     * Fetches user full names for the given email addresses from external API
     *
     * @param emails List of user emails to fetch names for
     * @param authToken JWT token from the request
     * @return Map of email to full name
     */
    @Cacheable(value = ["userFullNames"], key = "#emails.toString() + '_' + #tenantId")
    fun fetchUserFullNames(
        emails: List<String>,
        authToken: String,
        tenantId: String = TenantContext.getCurrentTenant() ?: ""
    ): Map<String, String> {

        if (emails.isEmpty()) {
            return emptyMap()
        }

        logger.debug("Fetching user full names for emails: {} for tenant: {}", emails, tenantId)

        try {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            headers["X-Client"] = tenantId
            headers["Authorization"] = if (authToken.startsWith("Bearer ")) authToken else "Bearer $authToken"

            val requestBody = UserFullNameRequest(emails = emails)
            val entity = HttpEntity(requestBody, headers)

            val response = restTemplate.exchange(
                userServiceUrl,
                HttpMethod.POST,
                entity,
                String::class.java
            )

            if (response.statusCode == HttpStatus.OK) {
                val userResponse = objectMapper.readValue(response.body, UserFullNameResponse::class.java)

                if (userResponse.success && userResponse.data != null) {
                    logger.debug("Successfully fetched {} user full names", userResponse.data.size)
                    return userResponse.data
                } else {
                    logger.warn("User full name fetch was not successful: {}", userResponse.message)
                    return emptyMap()
                }
            } else {
                logger.error("Failed to fetch user full names. Status: {}", response.statusCode)
                return emptyMap()
            }

        } catch (e: RestClientException) {
            logger.error("Error fetching user full names from external API", e)
            return emptyMap()
        } catch (e: Exception) {
            logger.error("Unexpected error while fetching user full names", e)
            return emptyMap()
        }
    }

    /**
     * Enriches a list of objects with user full names
     * Assumes objects have an email field
     */
    fun <T> enrichWithUserFullNames(
        items: List<T>,
        emailExtractor: (T) -> String?,
        fullNameSetter: (T, String?) -> Unit,
        authToken: String
    ): List<T> {
        val uniqueEmails = items
            .mapNotNull(emailExtractor)
            .distinct()

        if (uniqueEmails.isEmpty()) {
            return items
        }

        val userFullNames = fetchUserFullNames(uniqueEmails, authToken)

        items.forEach { item ->
            val email = emailExtractor(item)
            if (email != null) {
                val fullName = userFullNames[email]
                fullNameSetter(item, fullName)
            }
        }

        return items
    }
}
