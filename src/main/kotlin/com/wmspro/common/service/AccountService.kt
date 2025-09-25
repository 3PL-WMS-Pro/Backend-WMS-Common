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
class AccountService(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.account-service.url:https://cloud.leadtorev.com/clients/accounts/retrieve/account-names}")
    private lateinit var accountServiceUrl: String

    data class AccountNamesRequest(
        val accountIds: List<Long>
    )

    data class AccountNamesResponse(
        val success: Boolean,
        val message: String?,
        val data: Map<String, String>? // account_id -> account_name
    )

    /**
     * Fetches account names for the given account IDs from external API
     *
     * @param accountIds List of account IDs to fetch names for
     * @param authToken JWT token from the request
     * @return Map of account_id to account_name
     */
    @Cacheable(value = ["accountNames"], key = "#accountIds.toString() + '_' + #tenantId")
    fun fetchAccountNames(
        accountIds: List<Long>,
        authToken: String,
        tenantId: String = TenantContext.getCurrentTenant() ?: ""
    ): Map<String, String> {

        if (accountIds.isEmpty()) {
            return emptyMap()
        }

        logger.debug("Fetching account names for IDs: {} for tenant: {}", accountIds, tenantId)

        try {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            headers["X-Client"] = tenantId
            headers["Authorization"] = if (authToken.startsWith("Bearer ")) authToken else "Bearer $authToken"

            val request = AccountNamesRequest(accountIds)
            val entity = HttpEntity(request, headers)

            val response = restTemplate.exchange(
                accountServiceUrl,
                HttpMethod.POST,
                entity,
                String::class.java
            )

            if (response.statusCode == HttpStatus.OK) {
                val accountResponse = objectMapper.readValue(response.body, AccountNamesResponse::class.java)

                if (accountResponse.success && accountResponse.data != null) {
                    logger.debug("Successfully fetched {} account names", accountResponse.data.size)
                    return accountResponse.data
                } else {
                    logger.warn("Account name fetch was not successful: {}", accountResponse.message)
                    return emptyMap()
                }
            } else {
                logger.error("Failed to fetch account names. Status: {}", response.statusCode)
                return emptyMap()
            }

        } catch (e: RestClientException) {
            logger.error("Error fetching account names from external API", e)
            return emptyMap()
        } catch (e: Exception) {
            logger.error("Unexpected error while fetching account names", e)
            return emptyMap()
        }
    }

    /**
     * Enriches a list of objects with account names
     * Assumes objects have an 'account_id' field
     */
    fun <T> enrichWithAccountNames(
        items: List<T>,
        accountIdExtractor: (T) -> Long?,
        accountNameSetter: (T, String?) -> Unit,
        authToken: String
    ): List<T> {
        val uniqueAccountIds = items
            .mapNotNull(accountIdExtractor)
            .distinct()

        if (uniqueAccountIds.isEmpty()) {
            return items
        }

        val accountNames = fetchAccountNames(uniqueAccountIds, authToken)

        items.forEach { item ->
            val accountId = accountIdExtractor(item)
            if (accountId != null) {
                val accountName = accountNames[accountId.toString()]
                accountNameSetter(item, accountName)
            }
        }

        return items
    }
}