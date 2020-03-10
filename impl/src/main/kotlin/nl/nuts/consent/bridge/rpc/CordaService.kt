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

import net.corda.core.CordaRuntimeException
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.readFully
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.node.services.vault.SortAttribute
import net.corda.core.node.services.vault.builder
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import nl.nuts.consent.bridge.ConsentRegistryProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.api.NotFoundException
import nl.nuts.consent.bridge.conversion.BridgeToCordappType
import nl.nuts.consent.bridge.conversion.CordappToBridgeType
import nl.nuts.consent.bridge.model.ConsentRecord
import nl.nuts.consent.bridge.model.FullConsentRequestState
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.registry.apis.EndpointsApi
import nl.nuts.consent.flow.ConsentFlows
import nl.nuts.consent.flow.DiagnosticFlows
import nl.nuts.consent.model.ConsentMetadata
import nl.nuts.consent.schema.ConsentSchemaV1
import nl.nuts.consent.state.ConsentBranch
import nl.nuts.consent.state.ConsentState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.FileAlreadyExistsException
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

const val RPC_PROXY_ERROR = "Could not get a proxy to Corda RPC"
const val TIMEOUT_ERROR = "Operation timed out"

/**
 * Service for all Corda related logic. Primarily uses the CordaRPC functionality.
 */
@Service
class CordaService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var cordaRPClientFactory: CordaRPClientFactory
    lateinit var cordaRPClientWrapper: CordaRPClientWrapper

    @Autowired
    lateinit var consentRegistryProperties: ConsentRegistryProperties

    lateinit var endpointsApi: EndpointsApi

    /**
     * Initialize the CordaRPC connection
     */
    @PostConstruct
    fun init() {
        cordaRPClientWrapper = cordaRPClientFactory.getObject()
        endpointsApi = EndpointsApi(consentRegistryProperties.url)
    }

    /**
     * Close the Corda RPC connection
     */
    @PreDestroy
    fun destroy() {
        logger.debug("Stopping corda service")

        cordaRPClientWrapper.term()

        logger.info("Corda service stopped")
    }

    /**
     * Get a ConsentRequestState given its UUID
     *
     * @param UUID uuid part of the Corda UniqueIdentifier
     *
     * @return ConsentRequestState or NotFoundException when not found
     * @throws NotFoundException for not found
     * @throws IllegalStateException if more than 1 result is found or when an RPC connection could not be made
     */
    @Throws(NotFoundException::class)
    fun consentBranchByUUID(UUID: String) : ConsentBranch {
        val page : Vault.Page<ConsentBranch> = listConsentBranchByUUID(UUID)

        if (page.states.isEmpty()) {
            throw NotFoundException("No states found with linearId $UUID")
        }

        if (page.states.size > 1) {
            throw IllegalStateException("Too many states found with linearId $UUID")
        }

        val stateAndRef = page.states.first()
        return stateAndRef.state.data
    }

    /**
     * Check if a ConsentBranch already exists
     *
     * @param UUID the UUID of the consent branch
     *
     */
    fun consentBranchExists(UUID: String) : Boolean {
        val page : Vault.Page<ConsentBranch> = listConsentBranchByUUID(UUID)

        if (page.states.isEmpty()) {
            return false
        }

        if (page.states.size > 1) {
            throw IllegalStateException("Too many states found with linearId $UUID")
        }

        return false
    }

    private fun listConsentBranchByUUID(uuid: String) : Vault.Page<ConsentBranch> {
        val proxy = cordaRPClientWrapper.proxy() ?: throw IllegalStateException(RPC_PROXY_ERROR)

        closeConnectionOnError {
            val criteria = QueryCriteria.LinearStateQueryCriteria(participants = null,
                    linearId = listOf(UniqueIdentifier(null, UUID.fromString(uuid))),
                    status = Vault.StateStatus.UNCONSUMED,
                    contractStateTypes = setOf(nl.nuts.consent.state.ConsentBranch::class.java))

            return proxy.vaultQueryBy(
                    criteria = criteria,
                    paging = PageSpecification(),
                    sorting = Sort(emptySet()),
                    contractStateType = ConsentBranch::class.java
            )
        }
    }

    /**
     * convert a Corda ConsentRequestState to the service space event that needs to be published.
     * @param state ConsentRequestState
     * @return event with name "distributed consentRequest received"
     * @throws IllegalStateException when externalId on event is empty
     */
    fun consentBranchToEvent(state: ConsentBranch) : Event {

        val consentRecords = mutableListOf<ConsentRecord>()

        state.attachments.forEach { att ->
            val attachment= getCipherText(state.attachments.first()) ?: throw IllegalStateException("Attachment with ID ${state.attachments.first()} does not exist")
            val hash = att.toString()
            consentRecords.add(ConsentRecord(
                    metadata = CordappToBridgeType.convert(attachment.metadata),
                    cipherText = Base64.getEncoder().encodeToString(attachment.data),
                    attachmentHash = hash,
                    signatures = state.signatures.filter { sig -> sig.attachmentHash.toString() == hash }.map { CordappToBridgeType.convert(it) }
            ))
        }

        val crs = FullConsentRequestState(
                consentId = CordappToBridgeType.convert(state.linearId),
                legalEntities = state.legalEntities.toList(),
                consentRecords = consentRecords
        )

        val crsBytes = Serialization.objectMapper().writeValueAsBytes(crs)
        val crsBase64 = Base64.getEncoder().encodeToString(crsBytes)

        val eId = state.linearId.externalId ?: throw IllegalStateException("externalId is required on event and empty for consentStateUUID")

        // the uuid of the event equals the uuid of the ConsentBranch which equals the uuid of the event at the originating side
        return Event(
                UUID = state.linearId.id.toString(),
                name = EventName.EventDistributedConsentRequestReceived,
                retryCount = 0,
                externalId = eId,
                consentId = state.linearId.id.toString(),
                payload = crsBase64
        )
    }

    /**
     * convert a Corda ConsentState to the service space event that needs to be published.
     * @param state ConsentRequestState
     * @return event with name "consent distributed"
     * @throws IllegalStateException when externalId on event is empty
     */
    fun consentStateToEvent(state: ConsentState) : Event {

        val consentRecords = mutableListOf<ConsentRecord>()

        state.attachments.forEach { att ->
            val attachment= getCipherText(att) ?: throw IllegalStateException("Attachment with ID ${state.attachments.first()} does not exist")
            consentRecords.add(ConsentRecord(
                    metadata = CordappToBridgeType.convert(attachment.metadata),
                    cipherText = Base64.getEncoder().encodeToString(attachment.data),
                    attachmentHash = att.toString(),
                    signatures = emptyList()
            ))
        }

        val cs = nl.nuts.consent.bridge.model.ConsentState(
                consentId = CordappToBridgeType.convert(state.linearId),
                consentRecords = consentRecords
        )

        val csBytes = Serialization.objectMapper().writeValueAsBytes(cs)
        val csBase64 = Base64.getEncoder().encodeToString(csBytes)

        val eId = state.linearId.externalId ?: throw IllegalStateException("externalId is required on event and empty for consentStateUUID")

        return Event(
                UUID = UUID.randomUUID().toString(),
                name = EventName.EventConsentDistributed,
                retryCount = 0,
                externalId = eId,
                consentId = state.linearId.id.toString(),
                payload = csBase64
        )
    }

    /**
     * Get the cipherText bashed on the hash of the attachment (Sha256)
     * @param secureHash sha256 of attachment bytes
     * @throws IllegalStateException if no Corda RPC connection is available
     */
    fun getCipherText(secureHash: SecureHash) : Attachment? {
        val proxy =  cordaRPClientWrapper.proxy() ?: throw IllegalStateException(RPC_PROXY_ERROR)

        closeConnectionOnError {
            if (!proxy.attachmentExists(secureHash)) {
                return null
            }

            val zipInputStream = ZipInputStream(proxy.openAttachment(secureHash))

            var metadata: ConsentMetadata? = null
            var attachment = ByteArray(0)

            zipInputStream.use {
                do {
                    var entry: ZipEntry? = zipInputStream.nextEntry

                    if (entry == null) {
                        break
                    }

                    if (entry.name.endsWith(".json")) {
                        val reader = zipInputStream.bufferedReader()
                        val content = reader.readText()
                        metadata = Serialization.objectMapper().readValue(content, ConsentMetadata::class.java)
                    } else if (entry.name.endsWith(".bin")) {
                        attachment = readZipBytes(zipInputStream.buffered())

                        logger.trace("Retrieved cipherText from Corda containing:")
                        logger.trace(Base64.getEncoder().encodeToString(attachment))
                    }
                } while (entry != null)
            }

            val m = metadata ?: throw IllegalStateException("attachment does not contain a valid metadata file")
            if (attachment.isEmpty()) {
                throw IllegalStateException("attachment does not contain a valid binary file")
            }

            return Attachment(m, attachment)
        }
    }

    /**
     * Get the attachment bashed on the hash (Sha256)
     * @param secureHash sha256 of attachment bytes
     * @throws IllegalStateException if no Corda RPC connection is available
     */
    fun getAttachment(secureHash: SecureHash) : ByteArray? {
        val proxy =  cordaRPClientWrapper.proxy() ?: throw IllegalStateException(RPC_PROXY_ERROR)

        closeConnectionOnError {
            if (!proxy.attachmentExists(secureHash)) {
                return null
            }

            val inputStream = proxy.openAttachment(secureHash)
            return inputStream.readFully()
        }
    }

    private fun readZipBytes(reader : BufferedInputStream) : ByteArray {
        var attachment = ByteArray(0)
        val buffer = ByteArray(4096)
        var read: Int

        do {
            read = reader.read(buffer)
            if (read != -1) {
                val newAtt = ByteArray(attachment.size + read)
                System.arraycopy(attachment, 0, newAtt, 0, attachment.size)
                System.arraycopy(buffer, 0, newAtt, attachment.size, read)
                attachment = newAtt
            }
        } while (read != -1)

        return attachment
    }

    /**
     * Start the Corda flow for creating a new consentRequest state.
     * @param newConsentRequestState the consentRequestState to be created
     * @return a handle to the transaction. The transaction id is stored in events for tracking purposes
     * @throws IllegalStateException when an RPC connection could not be made
     */
    fun createConsentBranch(newConsentRequestState: FullConsentRequestState): FlowHandle<SignedTransaction> {
        //logger.debug("createConsentBranch() with {}", Serialization.objectMapper().writeValueAsString(newConsentRequestState))
        val proxy = cordaRPClientWrapper.proxy() ?: throw IllegalStateException(RPC_PROXY_ERROR)

        val externalId = newConsentRequestState.consentId.externalId ?: throw IllegalArgumentException("consentRequestState.consentId.externalId can not be empty")

        // find the previous state
        val consentState = findCurrentConsentState(externalId)

        val hashes = mutableSetOf<SecureHash>()

        // upload all attachments
        newConsentRequestState.consentRecords.forEach { cr ->
            // serialize consentRequestMetadata.metadata into 'metadata-[hash].json'
            val metadata = cr.metadata ?: throw IllegalArgumentException("consentRecord.metadata can not be empty")
            val cipherText = cr.cipherText ?: throw IllegalArgumentException("consentRecord.cipherText can not be empty")

            // gather orgIds from metadata
            val orgIds = metadata.organisationSecureKeys.map { it.legalEntity }

            if (!newConsentRequestState.legalEntities.toTypedArray().contentDeepEquals(orgIds.toTypedArray())) {
                throw java.lang.IllegalArgumentException("list of legalEntities not consistent over consent records and request record")
            }

            // create zip file with metadata file and attachment
            val zipBytes = createZip(metadata, cipherText)

            // upload attachment
            hashes.add(uploadAttachment(zipBytes))

        }

        // todo orgIds for all attachments must be the same!

        // todo: magic string
        val orgIds = newConsentRequestState.legalEntities
        val endpoints = endpointsApi.endpointsByOrganisationId(orgIds.toTypedArray(), "urn:nuts:endpoint:consent", true)

        if (endpoints.isEmpty()) {
            throw IllegalArgumentException("No available endpoints for given organization ids in registry")
        }

        closeConnectionOnError {
            // check consistency of nodeNames
            // urn:ietf:rfc:1779:X to X
            val nodeNames = endpoints.map {
                val name = CordaX500Name.parse(it.identifier.split(":").last())
                proxy.wellKnownPartyFromX500Name(name)
                    ?: throw IllegalStateException("Party with name $name does not exist in the network")
                name
            }.toSet()

            // start flow
            return proxy.startFlow(
                ConsentFlows::CreateConsentBranch,
                UUID.fromString(newConsentRequestState.consentId.UUID),
                consentState.linearId,
                hashes,
                orgIds.toSet(),
                nodeNames)
        }
    }

    private fun findCurrentConsentState(externalId: String) : ConsentState {
        // not autoclose, but reuse instance
        val proxy = cordaRPClientWrapper.proxy() ?: throw IllegalStateException(RPC_PROXY_ERROR)

        val customCriteriaI = builder { ConsentSchemaV1.PersistentConsent::externalId.equal(externalId) }
        val customCriteria = QueryCriteria.VaultCustomQueryCriteria(customCriteriaI, Vault.StateStatus.UNCONSUMED, setOf(ConsentState::class.java))
        val sortAttribute = SortAttribute.Custom(entityStateClass = ConsentSchemaV1.PersistentConsent::class.java, entityStateColumnName = "externalId")

        closeConnectionOnError {
            val page: Vault.Page<ConsentState> = proxy.vaultQueryBy(
                criteria = customCriteria,
                paging = PageSpecification(),
                sorting = Sort(setOf(Sort.SortColumn(sortAttribute, Sort.Direction.DESC))),
                contractStateType = ConsentState::class.java
            )

            if (page.states.isEmpty()) {
                // create Genesis
                val f = proxy.startFlow(
                    ConsentFlows::CreateGenesisConsentState,
                    externalId)

                val tx = f.returnValue.getOrThrow(15.seconds)
                return tx.coreTransaction.outputsOfType<ConsentState>().first()
            }

            val stateAndRef = page.states.first()
            return stateAndRef.state.data
        }
    }


    private fun createZip(metadata: nl.nuts.consent.bridge.model.Metadata, cipherText: String) : ByteArray {
        // serialize consentRequestMetadata.metadata into 'metadata-[hash].json'
        val targetMetadata = BridgeToCordappType.convert(metadata)
        val metadataBytes = Serialization.objectMapper().writeValueAsBytes(targetMetadata)
        val metadataHash = SecureHash.sha256(metadataBytes)

        // attachment hash name component
        var attachmentBytes: ByteArray?
        try {
            attachmentBytes = Base64.getDecoder().decode(cipherText)

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

            logger.trace("wrote following bytes to zip: ")
            logger.trace(Base64.getEncoder().encodeToString(attachmentBytes))
        }

        return targetStream.toByteArray()
    }

    private fun uploadAttachment(data: ByteArray) : SecureHash {
        var hash : SecureHash
        val proxy = cordaRPClientWrapper.proxy() ?: throw IllegalStateException(RPC_PROXY_ERROR)

        closeConnectionOnError {
            try {
                hash = proxy.uploadAttachment(BufferedInputStream(ByteArrayInputStream(data)))
            } catch (e : CordaRuntimeException) {
                if (e.cause is FileAlreadyExistsException) {
                    val ex = e.cause as FileAlreadyExistsException
                    hash = SecureHash.parse(ex.file)
                    logger.warn("Duplicate attachment, using $hash identifier for existing attachment")
                } else {
                    throw e
                }
            }
            return hash
        }
    }

    /**
     * start a Corda acceptConsentRequest flow indicating a legalEntity acknowledged the content and added a signature
     * @param uuid UUID part of uniqueIdentifier of consentRequest state in corda
     * @param partyAttachmentSignature signature of a legalEntity plus public key
     * @return a handle to the transaction. The transaction id is stored in events for tracking purposes
     * @throws IllegalStateException when an RPC connection could not be made
     */
    fun signConsentBranch(uuid: String, partyAttachmentSignature: PartyAttachmentSignature): FlowHandle<SignedTransaction> {
        val proxy = cordaRPClientWrapper.proxy() ?: throw IllegalStateException(RPC_PROXY_ERROR)

        closeConnectionOnError {
            return proxy.startFlow(
                ConsentFlows::SignConsentBranch,
                UniqueIdentifier(id = UUID.fromString(uuid)),
                listOf(BridgeToCordappType.convert(partyAttachmentSignature)))
        }
    }

    /**
     * start a Corda finalizeConsentRequest flow indicating all legalEntities acknowledged the content. The consentRequestState is ready to convert to consentState
     * @param uuid UUID part of uniqueIdentifier of consentRequest state in corda
     * @param partyAttachmentSignature signature of a legalEntity plus public key
     * @return a handle to the transaction. The transaction id is stored in events for tracking purposes
     * @throws IllegalStateException when an RPC connection could not be made
     */
    fun mergeConsentBranch(uuid: String): FlowHandle<SignedTransaction> {
        val proxy = cordaRPClientWrapper.proxy() ?: throw IllegalStateException(RPC_PROXY_ERROR)

        closeConnectionOnError {
            return proxy.startFlow(
                ConsentFlows::MergeBranch,
                UniqueIdentifier(id = UUID.fromString(uuid))
            )
        }
    }

    /**
     * find a consumed ConsentBranch State given a Transaction Hash. Used to find the ConsentBranch UUID after a merge
     * @param tx Transaction hash
     * @return the UUID from the ConsentBranch UniqueIdentifier or null if something can't be found
     */
    fun consentBranchByTx(tx: SecureHash) : UUID? {
        val proxy = cordaRPClientWrapper.proxy() ?: throw IllegalStateException(RPC_PROXY_ERROR)

        closeConnectionOnError {
            val transaction = proxy.internalFindVerifiedTransaction(tx) ?: return null

            val criteria = QueryCriteria.VaultQueryCriteria(stateRefs = transaction.inputs, status = Vault.StateStatus.CONSUMED)
            val results = proxy.vaultQueryBy<ConsentBranch>(criteria)

            if (results.states.size != 1) {
                logger.debug("Found multiple consumed ConsentBranch states for tx: $tx")
                return null
            }

            return results.states[0].state.data.linearId.id
        }
    }

    /**
     * Start the PingNotaryFlow
     * Expects answer within 10 seconds
     *
     * @return PingResult with success as true/false and an error description when false
     */
    fun pingNotary() : PingResult {
        val proxy = cordaRPClientWrapper.proxy() ?: return PingResult(false, RPC_PROXY_ERROR)

        closeConnectionOnError {
            try {
                val f = proxy.startFlow(DiagnosticFlows::PingNotaryFlow)

                f.returnValue.get(10, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                return PingResult(false, TIMEOUT_ERROR)
            }
            return PingResult(true)
        }
    }

    /**
     * Start the PingRandomFlow
     * Expects answer within 10 seconds
     *
     * @return PingResult with success as true/false and an error description when false
     */
    fun pingRandom() : PingResult {
        val proxy = cordaRPClientWrapper.proxy() ?: return PingResult(false, RPC_PROXY_ERROR)

        closeConnectionOnError {
            try {
                val f = proxy.startFlow(DiagnosticFlows::PingRandomFlow)

                f.returnValue.get(10, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                return PingResult(false, TIMEOUT_ERROR)
            }
            return PingResult(true)
        }
    }

    private fun logError(e:Exception) {
        logger.error(e.message)
    }

    private inline fun <R> closeConnectionOnError(block: () -> R): R {
        try{
            return block()
        } catch (e: Exception){
            // do your handle actions
            logger.error(e.message, e)
            cordaRPClientWrapper.close()
            throw e
        }
    }

    /**
     * helper class, represents the attachment zip
     */
    data class Attachment (
        val metadata: ConsentMetadata,
        val data: ByteArray
    )

    /**
     * Helper for storing result of ping action
     */
    data class PingResult (
        val success: Boolean,
        val error: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )
}