/*
 * Nuts consent bridge
 * Copyright (C) 2019 Nuts community
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package nl.nuts.consent.bridge.api

import net.corda.core.crypto.SecureHash
import nl.nuts.consent.bridge.model.ConsentRequestState
import nl.nuts.consent.bridge.rpc.CordaService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.convert.ConversionService
import org.springframework.stereotype.Service

/**
 * Concrete implementation of the ConsentApiService. This class connects our custom logic to the generated API's
 */
@Service
class ConsentApiServiceImpl : ConsentApiService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Qualifier("mvcConversionService")
    @Autowired
    lateinit var conversionService: ConversionService

    @Autowired
    lateinit var cordaService : CordaService

    // todo: change spec to reflect string is in hexadecimal notation
    override fun getAttachmentBySecureHash(secureHash: String): ByteArray {
        logger.debug("getAttachmentBySecureHash({})", secureHash)

        val hash = SecureHash.parse(secureHash)
        val attachment = cordaService.getAttachment(hash) ?: throw NotFoundException("Attachment with hash $secureHash not found")

        return attachment.data
    }

    override fun getConsentRequestStateById(uuid: String): ConsentRequestState {
        logger.debug("getConsentRequestStateById({})", uuid)

        return conversionService.convert(cordaService.consentRequestStateByUUID(uuid), nl.nuts.consent.bridge.model.ConsentRequestState::class.java)!!
    }
}