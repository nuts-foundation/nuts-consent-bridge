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
import nl.nuts.consent.bridge.model.ConsentRequestMetadata
import nl.nuts.consent.bridge.model.ConsentRequestState
import nl.nuts.consent.bridge.model.EventStreamSetting
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.zmq.Publisher
import nl.nuts.consent.bridge.zmq.Subscription
import nl.nuts.consent.flow.ConsentRequestFlows
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.*
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

    override fun acceptConsentRequestState(uuid: String, partyAttachmentSignature: PartyAttachmentSignature): String {
        val proxy = cordaRPClientWrapper.proxy()

        val handle = proxy.startFlow(
                ConsentRequestFlows::AcceptConsentRequest,
                UniqueIdentifier("dummy", UUID.fromString(uuid)),
                listOf(partyAttachmentSignature.convert()))


        // todo: do something with the result?, eg make async for logging purposes?
        return "OK"
    }

    override fun finalizeConsentRequestState(uuid: String): String {
        val proxy = cordaRPClientWrapper.proxy()

        val handle = proxy.startFlow(
                ConsentRequestFlows::FinalizeConsentRequest,
                UniqueIdentifier("dummy", UUID.fromString(uuid)))


        // todo: do something with the result?, eg make async for logging purposes?
        return "OK"
    }

    override fun newConsentRequestState(consentRequestMetadata: ConsentRequestMetadata, attachment: MultipartFile): String {
        val proxy = cordaRPClientWrapper.proxy()

        // serialize consentRequestMetadata.metadata into metadata-hash.json
        val metadataBytes = Serialisation.objectMapper().writeValueAsBytes(consentRequestMetadata.metadata)
        val metadataHash = SecureHash.sha256(metadataBytes)

        // attachment hash name component
        val attachmentHash = SecureHash.sha256(attachment.bytes)

        // create zip file with metadata file and attachment
        val targetStream = ByteArrayOutputStream()
        val zipOut = ZipOutputStream(BufferedOutputStream(targetStream))
        zipOut.use {
            it.putNextEntry(ZipEntry("metadata-${metadataHash}.json"))
            it.write(metadataBytes)

            it.putNextEntry(ZipEntry("cipher_text-${attachmentHash}.bin"))
            it.write(attachment.bytes)
        }

        // upload attachment
        val hash = proxy.uploadAttachment(BufferedInputStream(ByteArrayInputStream(targetStream.toByteArray())))

        // gather orgIds from metadata
        val orgIds = consentRequestMetadata.metadata.organisationSecureKeys.map { it.legalEntityURI }

        // todo: magic string
        val endpoints = endpointsApi.endpointsByOrganisationId(orgIds.toTypedArray(), "https://nuts.nl/CodeSystem/endpoint-type#consent")
        // todo: incompatible names
        val nodeNames = endpoints.map{ CordaX500Name.parse(it.identifier.value) }

        // start flow
        val handle = proxy.startFlow(
                ConsentRequestFlows::NewConsentRequest,
                consentRequestMetadata.externalId,
                setOf(hash),
                nodeNames)

        // todo: do something with the result?, eg make async for logging purposes?
        return "OK"
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
        // not autoclose, but reuse instance
        val proxy = cordaRPClientWrapper.proxy()

        val criteria = QueryCriteria.LinearStateQueryCriteria(participants = null,
            linearId = listOf(UniqueIdentifier("", UUID.fromString(uuid))),
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

        return state.convert()
    }

    override fun initEventStream(eventStreamSetting: EventStreamSetting) : String {
        publisher.addSubscription(Subscription(eventStreamSetting.topic, eventStreamSetting.epoch))
        return "OK"
    }
}