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

package nl.nuts.consent.bridge.rpc

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.transactions.SignedTransaction
import net.corda.nodeapi.exceptions.DuplicateAttachmentException
import nl.nuts.consent.bridge.ConsentRegistryProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.api.NotFoundException
import nl.nuts.consent.bridge.conversion.BridgeToCordappType
import nl.nuts.consent.bridge.conversion.CordappToBridgeType
import nl.nuts.consent.bridge.model.NewConsentRequestState
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.registry.apis.EndpointsApi
import nl.nuts.consent.flow.ConsentRequestFlows
import nl.nuts.consent.model.ConsentMetadata
import nl.nuts.consent.state.ConsentRequestState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class CordaService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var cordaRPClientFactory: CordaRPClientFactory
    lateinit var cordaRPClientWrapper: CordaRPClientWrapper

    @Autowired
    lateinit var consentRegistryProperties: ConsentRegistryProperties

    lateinit var endpointsApi: EndpointsApi

    @PostConstruct
    fun init() {
        cordaRPClientWrapper = cordaRPClientFactory.getObject()
        endpointsApi = EndpointsApi(consentRegistryProperties.url)
    }

    @PreDestroy
    fun destroy() {
        logger.debug("Stopping corda service")

        cordaRPClientWrapper.term()

        logger.info("Corda service stopped")
    }

    fun cordaRPClientWrapper() : CordaRPClientWrapper {
        return cordaRPClientWrapper
    }

    @Throws(NotFoundException::class)
    fun consentRequestStateByUUID(uuid: String) : ConsentRequestState {
        // not autoclose, but reuse instance
        val proxy = cordaRPClientWrapper.proxy()

        val criteria = QueryCriteria.LinearStateQueryCriteria(participants = null,
                linearId = listOf(UniqueIdentifier(null, UUID.fromString(uuid))),
                status = Vault.StateStatus.UNCONSUMED,
                contractStateTypes = setOf(nl.nuts.consent.state.ConsentRequestState::class.java))

        val page : Vault.Page<ConsentRequestState> = proxy!!.vaultQueryBy(
                criteria = criteria,
                paging = PageSpecification(),
                sorting = Sort(emptySet()),
                contractStateType = ConsentRequestState::class.java
        )

        if (page.states.isEmpty()) {
            throw NotFoundException("No states found with linearId $uuid")
        }

        if (page.states.size > 1) {
            throw IllegalStateException("Too many states found with linearId $uuid")
        }

        val stateAndRef = page.states.first()
        return stateAndRef.state.data
    }

    fun contractStateToEvent(state: ConsentRequestState) : Event {

        val attachment= getAttachment(state.attachments.first()) ?: throw IllegalStateException("Attachment with ID ${state.attachments.first()} does not exist")


        val ncrs =  NewConsentRequestState(
                externalId = state.consentStateUUID.externalId!!,
                metadata = CordappToBridgeType.convert(attachment.metadata),
                attachment = Base64.getEncoder().encodeToString(attachment.data)
        )

        val ncrsBytes = Serialization.objectMapper().writeValueAsBytes(ncrs)
        val ncrsBase64 = Base64.getEncoder().encodeToString(ncrsBytes)

        return Event(
                UUID = UUID.randomUUID().toString(),
                name = EventName.EventDistributedConsentRequestReceived,
                retryCount = 0,
                externalId = state.consentStateUUID.externalId!!,
                consentId = state.consentStateUUID.id.toString(),
                payload = ncrsBase64
        )
    }

    fun getAttachment(secureHash: SecureHash) : Attachment? {
        val proxy =  cordaRPClientWrapper.proxy()!!
        if(!proxy.attachmentExists(secureHash)) {
            return null
        }

        val zipInputStream = ZipInputStream(proxy.openAttachment(secureHash))

        var metadata: ConsentMetadata? = null
        var attachment:ByteArray? = null

        zipInputStream.use {
            do {
                var entry: ZipEntry? = zipInputStream.nextEntry

                if (entry == null) {
                    break
                }

                if (entry.name.endsWith(".json")) {
                    val reader = zipInputStream.bufferedReader(Charset.forName("UTF8"))
                    val content = reader.readText()
                    metadata = Serialization.objectMapper().readValue(content, ConsentMetadata::class.java)
                } else if (entry.name.endsWith(".bin")) {
                    val reader = zipInputStream.bufferedReader()
                    attachment = reader.readText().toByteArray()
                }
            } while (entry != null)
        }

        if (metadata == null) {
            throw IllegalStateException("attachment does not contain a valid metadata file")
        }
        if (attachment == null) {
            throw IllegalStateException("attachment does not contain a valid binary file")
        }

        return Attachment(metadata!!, attachment!!)
    }

    fun newConsentRequestState(newConsentRequestState: NewConsentRequestState): FlowHandle<SignedTransaction> {
        logger.debug("newConsentRequestState() with {}", Serialization.objectMapper().writeValueAsString(newConsentRequestState))
        val proxy = cordaRPClientWrapper.proxy()!!

        // serialize consentRequestMetadata.metadata into 'metadata-[hash].json'
        val targetMetadata = BridgeToCordappType.convert(newConsentRequestState.metadata)
        val metadataBytes = Serialization.objectMapper().writeValueAsBytes(targetMetadata)
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
        var hash : SecureHash
        try {
            hash = proxy.uploadAttachment(BufferedInputStream(ByteArrayInputStream(targetStream.toByteArray())))
        } catch (e : FileAlreadyExistsException) {
            hash = SecureHash.parse(e.file)
        }

        // gather orgIds from metadata
        val orgIds = newConsentRequestState.metadata.organisationSecureKeys.map { it.legalEntity }.toSet()

        // todo: magic string
        val endpoints = endpointsApi.endpointsByOrganisationId(orgIds.toTypedArray(), "urn:nuts:endpoint:consent")

        if (endpoints.size < 1) {
            throw IllegalArgumentException("No available endpoints for given organization ids in registry")
        }

        // urn:ietf:rfc:1779:X to X
        val nodeNames = endpoints.map{ CordaX500Name.parse(it.identifier.split(":").last()) }.toSet()

        // start flow
        return proxy.startFlow(
                ConsentRequestFlows::NewConsentRequest,
                newConsentRequestState.externalId,
                setOf(hash),
                orgIds,
                nodeNames)
    }

    fun acceptConsentRequestState(uuid: String, partyAttachmentSignature: PartyAttachmentSignature): FlowHandle<SignedTransaction> {
        val proxy = cordaRPClientWrapper.proxy()!!

        return proxy.startFlow(
                ConsentRequestFlows::AcceptConsentRequest,
                UniqueIdentifier(id = UUID.fromString(uuid)),
                listOf(BridgeToCordappType.convert(partyAttachmentSignature)))
    }

    fun finalizeConsentRequestState(uuid: String): FlowHandle<SignedTransaction> {
        val proxy = cordaRPClientWrapper.proxy()!!

        return proxy.startFlow(
                ConsentRequestFlows::FinalizeConsentRequest,
                UniqueIdentifier(id = UUID.fromString(uuid))
        )
    }

    data class Attachment (
        val metadata: ConsentMetadata,
        val data: ByteArray
    )
}