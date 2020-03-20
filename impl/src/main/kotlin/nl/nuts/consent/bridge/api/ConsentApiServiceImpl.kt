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
import nl.nuts.consent.bridge.ConsentRegistryProperties
import nl.nuts.consent.bridge.conversion.CordappToBridgeType.Companion.convert
import nl.nuts.consent.bridge.corda.CordaManagedConnection
import nl.nuts.consent.bridge.corda.CordaManagedConnectionFactory
import nl.nuts.consent.bridge.corda.CordaService
import nl.nuts.consent.bridge.model.FullConsentRequestState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct

/**
 * Concrete implementation of the ConsentApiService. This class connects our custom logic to the generated API's
 */
@Service
class ConsentApiServiceImpl : ConsentApiService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var cordaManagedConnectionFactory: CordaManagedConnectionFactory

    @Autowired
    lateinit var consentRegistryProperties: ConsentRegistryProperties

    protected lateinit var cordaService: CordaService

    /**
     * initialize the service, sets the correct name on the acquired corda connection
     */
    @PostConstruct
    fun init() {
        val cordaManagedConnection = cordaManagedConnectionFactory.`object`
        cordaManagedConnection.name = "api"
        cordaService = CordaService(cordaManagedConnection, consentRegistryProperties)
    }

    /**
     * Get the attachment by its secure hash
     * @param secureHash Sha256 of attachment bytes, used as Id
     *
     * @return OctetStream of bytes
     */
    // todo: change spec to reflect string is in hexadecimal notation
    override fun getAttachmentBySecureHash(secureHash: String): ByteArray {
        logger.debug("getAttachmentBySecureHash({})", secureHash)

        val hash = SecureHash.parse(secureHash)
        return cordaService.getAttachment(hash) ?: throw NotFoundException("Attachment with hash $secureHash not found")
    }

    /**
     * Get the consentRequest state by the UUID
     *
     * @param uuid (UUID part of the UniqueIdentifier)
     *
     * @return FullConsentRequestState with consentId, attachmentHashes, legalEntities and signatures
     */
    override fun getConsentRequestStateById(uuid: String): FullConsentRequestState {
        logger.debug("getConsentRequestStateById({})", uuid)

        return convert(cordaService.consentBranchByUUID(uuid))
    }
}