package com.wmspro.common.external.freighai.parser

import com.wmspro.common.service.UserService
import org.springframework.stereotype.Component

/**
 * Pure translator: FreighAI user DTOs → WMS-shaped UserService response.
 *
 * The shape is the same on both sides (Map<email, fullName>), so this is
 * effectively a wrapper around the success envelope. Kept as a separate class
 * for symmetry with FreighAiAccountParser and to centralize where future
 * shape divergence would land.
 */
@Component
class FreighAiUserParser {

    /**
     * Wrap a FreighAI batch-by-emails result in WMS's UserFullNameResponse.
     * Empty input or empty result → success=true with empty data map.
     */
    fun toUserFullNameResponse(emailToName: Map<String, String>): UserService.UserFullNameResponse {
        return UserService.UserFullNameResponse(
            success = true,
            message = null,
            data = emailToName
        )
    }
}
