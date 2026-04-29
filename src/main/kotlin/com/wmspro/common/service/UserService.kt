package com.wmspro.common.service

import com.wmspro.common.external.freighai.client.FreighAiUserClient
import com.wmspro.common.external.freighai.parser.FreighAiUserParser
import com.wmspro.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * UserService — resolves user emails to display names from the external user-master
 * provider. WMS warehouse documents track activity by email (`receivingStaff`,
 * `assignedTo`, `createdBy`, `statusHistory[].changedBy`, etc.); this service maps
 * those emails to the human-readable name shown in the UI.
 *
 * Migration history (D2 / D14):
 *   - Pre-2026-04-29: HTTP-direct to leadtorev `/users/get/email-to-fullname-mapping`.
 *   - Phase 4 onwards: routes through FreighAi's POST `/api/v1/users/batch-by-emails`.
 *
 * Email is the join key on both sides (no ID translation needed since the same emails
 * exist in both leadtorev and FreighAi for Infinity Logistics' staff). Public method
 * signatures, return types, and @Cacheable keys are unchanged.
 */
@Service
class UserService(
    private val freighAiUserClient: FreighAiUserClient,
    private val freighAiUserParser: FreighAiUserParser
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class UserFullNameRequest(
        val emails: List<String>
    )

    data class UserFullNameResponse(
        val success: Boolean,
        val message: String?,
        val data: Map<String, String>? // email -> fullName
    )

    /**
     * Fetches user full names for the given email addresses.
     *
     * @return Map of email → full name. Emails without a matching FreighAi user are
     *         silently omitted from the map.
     */
    @Cacheable(value = ["userFullNames"], key = "#emails.toString() + '_' + #tenantId")
    fun fetchUserFullNames(
        emails: List<String>,
        authToken: String,
        tenantId: String = TenantContext.getCurrentTenant() ?: ""
    ): Map<String, String> {

        if (emails.isEmpty()) return emptyMap()

        logger.debug("Fetching user full names for emails: {} (tenant {})", emails, tenantId)

        return try {
            val emailToName = freighAiUserClient.batchByEmails(emails, authToken)
            val response = freighAiUserParser.toUserFullNameResponse(emailToName)
            response.data ?: emptyMap()
        } catch (e: Exception) {
            logger.error("Unexpected error fetching user full names (input size={})", emails.size, e)
            emptyMap()
        }
    }

    /**
     * Convenience helper: enriches a list of arbitrary objects with user full names
     * by looking up via emailExtractor and writing back via fullNameSetter.
     * Unchanged from previous implementation.
     */
    fun <T> enrichWithUserFullNames(
        items: List<T>,
        emailExtractor: (T) -> String?,
        fullNameSetter: (T, String?) -> Unit,
        authToken: String
    ): List<T> {
        val uniqueEmails = items.mapNotNull(emailExtractor).distinct()

        if (uniqueEmails.isEmpty()) return items

        val userFullNames = fetchUserFullNames(uniqueEmails, authToken)

        items.forEach { item ->
            val email = emailExtractor(item)
            if (email != null) {
                fullNameSetter(item, userFullNames[email])
            }
        }

        return items
    }
}
