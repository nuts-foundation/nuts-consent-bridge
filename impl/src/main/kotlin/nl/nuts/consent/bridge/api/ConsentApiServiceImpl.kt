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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.readFully
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import nl.nuts.consent.bridge.ConsentRegistryProperties
import nl.nuts.consent.bridge.apis.EndpointsApi
import nl.nuts.consent.bridge.model.*
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.zmq.Publisher
import nl.nuts.consent.bridge.zmq.Subscription
import nl.nuts.consent.flow.ConsentRequestFlows
import nl.nuts.consent.contract.AttachmentSignature
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.convert.ConversionService
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.*
import java.lang.IllegalArgumentException
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.annotation.PostConstruct
import javax.annotation.Resource

/**
 * Concrete implementation of the ConsentApiService. This class connects our custom logic to the generated API's
 */
@Service
class ConsentApiServiceImpl : ConsentApiService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var publisher: Publisher

    @Autowired
    @Resource
    lateinit var cordaRPClientWrapper: CordaRPClientWrapper

    @Qualifier("mvcConversionService")
    @Autowired
    lateinit var conversionService: ConversionService

    @Autowired
    lateinit var consentRegistryProperties: ConsentRegistryProperties

    lateinit var endpointsApi: EndpointsApi

    object Serialisation {
        val _objectMapper : ObjectMapper by lazy {
            val objectMapper = ObjectMapper()
            objectMapper.registerModule(JavaTimeModule())
            objectMapper.dateFormat = SimpleDateFormat.getDateInstance()
            objectMapper
        }

        fun objectMapper() : ObjectMapper {
            return _objectMapper
        }
    }

    @PostConstruct
    fun init() {
        endpointsApi = EndpointsApi(consentRegistryProperties.url)
    }

    override fun acceptConsentRequestState(uuid: String, partyAttachmentSignature: PartyAttachmentSignature): ConsentRequestJobState {
        val proxy = cordaRPClientWrapper.proxy()

        val handle = proxy.startFlow(
                ConsentRequestFlows::AcceptConsentRequest,
                UniqueIdentifier("dummy", UUID.fromString(uuid)),
                listOf(conversionService.convert(partyAttachmentSignature, AttachmentSignature::class.java)))

        return ConsentRequestJobState(
                consentId = ConsentId(
                    UUID = uuid
                ),
                stateMachineId = handle.id.toString() // UUID
        )
    }

    override fun finalizeConsentRequestState(uuid: String): ConsentRequestJobState {
        val proxy = cordaRPClientWrapper.proxy()

        val handle = proxy.startFlow(
                ConsentRequestFlows::FinalizeConsentRequest,
                UniqueIdentifier("dummy", UUID.fromString(uuid)))

        return ConsentRequestJobState(
                consentId = ConsentId(
                        UUID = uuid
                ),
                stateMachineId = handle.id.toString() // UUID
        )
    }

    override fun newConsentRequestState(newConsentRequestState: NewConsentRequestState): ConsentRequestJobState {
        logger.debug("newConsentRequestState() with {}", Serialisation.objectMapper().writeValueAsString(newConsentRequestState))
        val proxy = cordaRPClientWrapper.proxy()

        // serialize consentRequestMetadata.metadata into 'metadata-[hash].json'
        val targetMetadata = conversionService.convert(newConsentRequestState.metadata, nl.nuts.consent.model.ConsentMetadata::class.java)
        val metadataBytes = Serialisation.objectMapper().writeValueAsBytes(targetMetadata)
        val metadataHash = SecureHash.sha256(metadataBytes)

        // attachment hash name component
        var attachmentBytes: ByteArray?
        try {
            attachmentBytes = Base64.getDecoder().decode(newConsentRequestState.attachment)
        } catch(e:IllegalArgumentException) {
            throw IllegalArgumentException("given attachment is not using valid base64 encoding: ${e.message}")
        }
        val attachmentHash = SecureHash.sha256(attachmentBytes)

        // create zip file with metadata file and attachment
        val targetStream = ByteArrayOutputStream()
        val zipOut = ZipOutputStream(BufferedOutputStream(targetStream))
        zipOut.use {
            it.putNextEntry(ZipEntry("metadata-${metadataHash}.json"))
            it.write(metadataBytes)

            it.putNextEntry(ZipEntry("cipher_text-${attachmentHash}.bin"))
            it.write(attachmentBytes)
        }

        // upload attachment
        val hash = proxy.uploadAttachment(BufferedInputStream(ByteArrayInputStream(targetStream.toByteArray())))

        // gather orgIds from metadata
        val orgIds = newConsentRequestState.metadata.organisationSecureKeys.map { it.legalEntity }.toSet()

        // todo: magic string
        val endpoints = endpointsApi.endpointsByOrganisationId(orgIds.toTypedArray(), "urn:nuts:endpoint:consent")

        // urn:ietf:rfc:1779:X to X
        val nodeNames = endpoints.map{ CordaX500Name.parse(it.identifier.split(":").last()) }.toSet()

        // start flow
        val handle = proxy.startFlow(
                ConsentRequestFlows::NewConsentRequest,
                newConsentRequestState.externalId,
                setOf(hash),
                orgIds,
                nodeNames)

        return ConsentRequestJobState(
                consentId = ConsentId(
                        externalId = newConsentRequestState.externalId
                ),
                stateMachineId = handle.id.toString() // UUID
        )
    }

    // todo: change spec to reflect string is in hexadecimal notation
    override fun getAttachmentBySecureHash(secureHash: String): ByteArray {
        val proxy = cordaRPClientWrapper.proxy()

        val hash = SecureHash.parse(secureHash)

        if(!proxy.attachmentExists(hash)) {
            throw NotFoundException("Attachment with hash $secureHash not found")
        }

        val stream = proxy.openAttachment(hash)

        // stream would have been better, but api spe code generation does not support it
        return stream.readFully()
    }

    override fun getConsentRequestStateById(uuid: String): ConsentRequestState {
        logger.debug("getConsentRequestStateById({})", uuid)

        // not autoclose, but reuse instance
        val proxy = cordaRPClientWrapper.proxy()

        val criteria = QueryCriteria.LinearStateQueryCriteria(participants = null,
            linearId = listOf(UniqueIdentifier(null, UUID.fromString(uuid))),
            status = Vault.StateStatus.UNCONSUMED,
            contractStateTypes = setOf(nl.nuts.consent.state.ConsentRequestState::class.java))

        val page : Vault.Page<nl.nuts.consent.state.ConsentRequestState> = proxy.vaultQueryBy(criteria = criteria)

        if (page.states.isEmpty()) {
            throw NotFoundException("No states found with linearId $uuid")
        }

        if (page.states.size > 1) {
            throw IllegalStateException("Too many states found with linearId $uuid")
        }

        val stateAndRef = page.states.first()
        val state = stateAndRef.state.data

        return conversionService.convert(state, nl.nuts.consent.bridge.model.ConsentRequestState::class.java)!!
    }

    override fun initEventStream(eventStreamSetting: EventStreamSetting) : String {
        publisher.addSubscription(Subscription(eventStreamSetting.topic, eventStreamSetting.epoch))
        return "OK"
    }
}