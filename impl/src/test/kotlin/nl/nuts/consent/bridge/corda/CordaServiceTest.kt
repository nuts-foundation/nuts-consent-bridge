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

package nl.nuts.consent.bridge.corda

import com.fasterxml.jackson.module.kotlin.readValue
import com.nhaarman.mockito_kotlin.*
import net.corda.core.CordaRuntimeException
import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.*
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.CoreTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import net.corda.nodeapi.exceptions.DuplicateAttachmentException
import nl.nuts.consent.bridge.ConsentRegistryProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.api.NotFoundException
import nl.nuts.consent.bridge.conversion.BridgeToCordappType
import nl.nuts.consent.bridge.model.*
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.registry.infrastructure.ClientException
import nl.nuts.consent.bridge.registry.models.Endpoint
import nl.nuts.consent.flow.ConsentFlows
import nl.nuts.consent.model.ConsentMetadata
import nl.nuts.consent.model.Domain
import nl.nuts.consent.model.Period
import nl.nuts.consent.model.SymmetricKey
import nl.nuts.consent.state.ConsentBranch
import nl.nuts.consent.state.ConsentState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith

class CordaServiceTest {

    val cordaRPCOps: CordaRPCOps = mock()
    val cordaManagedConnection: CordaManagedConnection = mock {
        on(it.proxy()) doReturn cordaRPCOps
    }

    val VALID_HEX = "afafafafafafafafafafafafafafafafafafafafafafafafafafafafafafafaf"

    val cordaName = CordaX500Name.parse("O=Nedap, OU=Healthcare, C=NL, ST=Gelderland, L=Groenlo, CN=nuts_corda_development_local")

    lateinit var cordaService : CordaService

    @Before
    fun setup() {
        cordaService = CordaService(cordaManagedConnection, ConsentRegistryProperties())
        cordaService.endpointsApi = mock()
    }

    @Test
    fun `ConsentBranchByUUID throws NotFoundException when proxy returns empty states`() {
        `when`(cordaRPCOps.vaultQueryBy(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentBranch::class.java))).thenReturn(branchPage(0))

        assertFailsWith<NotFoundException> {
            cordaService.consentBranchByUUID("1111-2222-33334444-5555-6666")
        }
    }

    @Test
    fun `ConsentBranchByUUID throws IllegalStateException when proxy returns more than 1 state`() {
        `when`(cordaRPCOps.vaultQueryBy(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentBranch::class.java))).thenReturn(branchPage(2))

        assertFailsWith<IllegalStateException> {
            cordaService.consentBranchByUUID("1111-2222-33334444-5555-6666")
        }
    }

    @Test
    fun `ConsentBranchByUUID state data on success`() {
        `when`(cordaRPCOps.vaultQueryBy(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentBranch::class.java))).thenReturn(branchPage(1))

        val state = cordaService.consentBranchByUUID("1111-2222-33334444-5555-6666")
        assertNotNull(state)
    }

    @Test
    fun `getAttachment returns null for unknown attachment`() {
        val hash = SecureHash.parse(VALID_HEX)

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(false)

        val att = cordaService.getCipherText(hash)

        assertNull(att)
    }

    @Test
    fun `getAttachment returns correct data for correct attachment`(){
        val hash = SecureHash.parse(VALID_HEX)

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(true)
        val bytes = ByteArray(8)
        bytes[0] = 1
        bytes[1] = 5
        bytes[2] = 20
        bytes[3] = 127
        bytes[4] = -1
        bytes[5] = -5
        bytes[6] = -20
        bytes[7] = -127
        `when`(cordaRPCOps.openAttachment(hash)).thenReturn(zip(consentMetadataAsJson(), bytes))

        val att = cordaService.getCipherText(hash)

        assertNotNull(att)
        assertTrue(att!!.metadata.domain.contains(Domain.medical))
        assertEquals("AQUUf//77IE=", Base64.getEncoder().encodeToString(att.data))
    }

    @Test
    fun `getAttachment throws IllegalState for missing metadata`(){
        val hash = SecureHash.parse(VALID_HEX)

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(hash)).thenReturn(zip(null, "blob".toByteArray()))


        assertFailsWith<IllegalStateException> {
            cordaService.getCipherText(hash)
        }
    }

    @Test
    fun `getAttachment throws IllegalState for missing binary`(){
        val hash = SecureHash.parse(VALID_HEX)

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(hash)).thenReturn(zip(consentMetadataAsJson(), null))


        assertFailsWith<IllegalStateException> {
            cordaService.getCipherText(hash)
        }
    }

    @Test
    fun `contractToStateEvent returns event for valid data`() {
        `when`(cordaRPCOps.attachmentExists(SecureHash.allOnesHash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(SecureHash.allOnesHash)).thenReturn(zip(consentMetadataAsJson(), "blob".toByteArray()))

        val event = cordaService.consentBranchToEvent(consentBranch())

        assertNotNull(event)

        // check payload
        val json = Base64.getDecoder().decode(event.payload)
        val fcrs : FullConsentRequestState = Serialization.objectMapper().readValue(json)

        assertEquals(EventName.EventDistributedConsentRequestReceived, event.name)
        assertEquals("externalId", event.externalId)
        assertNotNull( event.consentId)
        assertEquals(event.consentId, fcrs.consentId.UUID)
        assertEquals(event.externalId, fcrs.consentId.externalId)
    }

    @Test
    fun `contractToStateEvent raises for missing attachment`() {
        `when`(cordaRPCOps.attachmentExists(SecureHash.allOnesHash)).thenReturn(false)

        assertFailsWith<IllegalStateException> {
            cordaService.consentBranchToEvent(consentBranch())
        }
    }

    @Test
    fun `consentStateToEvent returns event for new state`() {
        `when`(cordaRPCOps.attachmentExists(SecureHash.allOnesHash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(SecureHash.allOnesHash)).thenReturn(zip(consentMetadataAsJson(), "blob".toByteArray()))

        val state = consentState()

        val event = cordaService.consentStateToEvent(state)

        assertEquals(state.linearId.externalId, event.externalId)
        assertEquals(state.linearId.id.toString(), event.consentId)
        assertEquals(EventName.EventConsentDistributed, event.name)
        assertEquals(0, event.retryCount)
    }

    @Test
    fun `consentStateToEvent returns event with correct payload for new state`() {
        `when`(cordaRPCOps.attachmentExists(SecureHash.allOnesHash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(SecureHash.allOnesHash)).thenReturn(zip(consentMetadataAsJson(), "blob".toByteArray()))

        val state = consentState()

        val b64Payload = cordaService.consentStateToEvent(state).payload
        val payload = Base64.getDecoder().decode(b64Payload)

        val cs = Serialization.objectMapper().readValue<nl.nuts.consent.bridge.model.ConsentState>(payload)

        assertEquals(1, cs.consentRecords.size)
        assertEquals(state.linearId.externalId, cs.consentId.externalId)
        assertEquals(state.linearId.id.toString(), cs.consentId.UUID)

        val cr = cs.consentRecords[0]

        assertEquals(SecureHash.allOnesHash.toString(), cr.attachmentHash)
        assertEquals(nl.nuts.consent.bridge.model.Domain.medical, cr.metadata!!.domain[0])
    }

    @Test
    fun `newConsentBranch raises for inconsistent legalEntitites`() {
        val newConsentBranch = newConsentBranch(emptyList())

        `when`(cordaRPCOps.vaultQueryBy(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentState::class.java))).thenReturn(statePage(1, UniqueIdentifier()))

        assertFailsWith<IllegalArgumentException> {
            cordaService.createConsentBranch(newConsentBranch)
        }
    }

    @Test
    fun `createConsentBranch raises on unknown nodeName`() {
        val newConsentBranch = newConsentBranch()
        val id = UniqueIdentifier(externalId = "externalId")

        `when`(cordaRPCOps.uploadAttachment(any())).thenReturn(SecureHash.allOnesHash)
        `when`(cordaService.endpointsApi.endpointsByOrganisationId(any(), any(), eq(false))).thenReturn(arrayOf(endpoint()))
        `when`(cordaRPCOps.wellKnownPartyFromX500Name(cordaName)).thenReturn(null)
        // simulate Genesis block
        `when`(cordaRPCOps.vaultQueryBy(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentState::class.java))).thenReturn(statePage(1, id))

        assertFailsWith<IllegalStateException> {
            cordaService.createConsentBranch(newConsentBranch)
        }
    }

    @Test
    fun `createConsentBranch returns FlowHandle on valid ConsentBranch`() {
        val newConsentBranch = newConsentBranch()
        val id = UniqueIdentifier(externalId = "externalId")

        `when`(cordaRPCOps.attachmentExists(any())).thenReturn(false)
        `when`(cordaRPCOps.uploadAttachment(any())).thenReturn(SecureHash.allOnesHash)
        `when`(cordaService.endpointsApi.endpointsByOrganisationId(any(), any(), eq(false))).thenReturn(arrayOf(endpoint()))
        `when`(cordaRPCOps.wellKnownPartyFromX500Name(cordaName)).thenReturn(mock())
        `when`(cordaRPCOps.startFlow(
                ConsentFlows::CreateConsentBranch,
                UUID.fromString(newConsentBranch.consentId.UUID),
                id,
                setOf(SecureHash.allOnesHash),
                setOf("legalEntity"),
                setOf(cordaName)
        )).thenReturn(FlowHandleImpl(StateMachineRunId.createRandom(), mock()))
        // simulate Genesis block
        `when`(cordaRPCOps.vaultQueryBy(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentState::class.java))).thenReturn(statePage(1, id))

        val handle = cordaService.createConsentBranch(newConsentBranch)

        assertNotNull(handle)
    }

    @Test
    fun `createConsentBranch returns FlowHandle on valid ConsentBranch with missing GenesisRecord`() {
        val newConsentBranch = newConsentBranch()
        val id = UniqueIdentifier(externalId = "externalId")

        val futureMock : CordaFuture<SignedTransaction> = mock()
        val txMock : SignedTransaction = mock()
        val coreTxMock : CoreTransaction = mock()
        `when`(futureMock.getOrThrow(15.seconds)).thenReturn(txMock)
        `when`(txMock.coreTransaction).thenReturn(coreTxMock)
        `when`(coreTxMock.outputsOfType<ConsentState>()).thenReturn(listOf(ConsentState(uuid = id, version = 1)))

        `when`(cordaRPCOps.attachmentExists(any())).thenReturn(false)
        `when`(cordaRPCOps.uploadAttachment(any())).thenReturn(SecureHash.allOnesHash)
        `when`(cordaService.endpointsApi.endpointsByOrganisationId(any(), any(), eq(false))).thenReturn(arrayOf(endpoint()))
        `when`(cordaRPCOps.wellKnownPartyFromX500Name(cordaName)).thenReturn(mock())
        `when`(cordaRPCOps.startFlow(
                ConsentFlows::CreateConsentBranch,
                UUID.fromString(newConsentBranch.consentId.UUID),
                id,
                setOf(SecureHash.allOnesHash),
                setOf("legalEntity"),
                setOf(cordaName)
        )).thenReturn(FlowHandleImpl(StateMachineRunId.createRandom(), mock()))
        // simulate Genesis block
        `when`(cordaRPCOps.vaultQueryBy(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentState::class.java))).thenReturn(statePage(0, id))
        // create genesis block
        `when`(cordaRPCOps.startFlow(
                ConsentFlows::CreateGenesisConsentState,
                "externalId"
        )).thenReturn(FlowHandleImpl(StateMachineRunId.createRandom(), futureMock))

        val handle = cordaService.createConsentBranch(newConsentBranch)

        assertNotNull(handle)
    }

    @Test
    fun `newConsentBranch throws exception on valid NewConsentBranch with duplicate attachment upload`() {
        val newConsentBranch = newConsentBranch()
        val id = UniqueIdentifier(externalId = "externalId")

        `when`(cordaRPCOps.uploadAttachment(any())).thenThrow(CordaRuntimeException("", DuplicateAttachmentException(SecureHash.allOnesHash.toString())))
        `when`(cordaService.endpointsApi.endpointsByOrganisationId(any(), any(), eq(false))).thenReturn(arrayOf(endpoint()))
        `when`(cordaRPCOps.wellKnownPartyFromX500Name(cordaName)).thenReturn(mock())
        `when`(cordaRPCOps.startFlow(
                ConsentFlows::CreateConsentBranch,
                UUID.fromString(newConsentBranch.consentId.UUID),
                id,
                setOf(SecureHash.allOnesHash),
                setOf("legalEntity"),
                setOf(cordaName)
        )).thenReturn(FlowHandleImpl(StateMachineRunId.createRandom(), mock()))
        // simulate Genesis block
        `when`(cordaRPCOps.vaultQueryBy(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentState::class.java))).thenReturn(statePage(1, id))

        assertThrows<CordaRuntimeException> {
            cordaService.createConsentBranch(newConsentBranch)
        }
    }


    @Test
    fun `acceptConsentBranch returns FlowHandle on valid partyAttachmentSignature`() {
        val uuid = "1111-2222-33334444-5555-6666"
        val attachment = VALID_HEX
        val partyAttachmentSignature = PartyAttachmentSignature(
                legalEntity = "legalEntity",
                attachment = attachment,
                signature = SignatureWithKey(
                        publicKey = mapOf(
                                "kty" to "RSA",
                                "e" to "AQAB",
                                "kid" to "17bf8a6f-0a0a-4bce-878c-4ac9b7447c64",
                                "n" to "wm7FBfggHaAfapO7TdFv0OwS-Ip9Wi7gyhddjmdZBZDzfYMUPr4-0utGM3Ry8JtCfxmsHL3ZmvG04GV1doeCLjLywm6OFfoEQCpliRiCyarpd2MrxKWjkSwOl9MJdVm3xpb7BWJdXkKEwoU4lBk8cZPay32juPzAV5eb6UCnq53PZ5O0H80J02oPLpBs2D6ASjUQpRf2xP0bvaP2W92PZYzJwrSA3zdxPmrMVApOoIZL7OHBE-y0I9ZUt-zmxD8TzRdN9Etf9wjLD7psu9aL_XHIHR0xMkYV8cr_nCbJ6H0PbDd3yIQvYPjLEVS5LeieN-DzIlYO6Y7kpws6k0rxew"
                        ),
                        data = "afaf"
                )
        )

        `when`(cordaRPCOps.startFlow(
                ConsentFlows::SignConsentBranch,
                UniqueIdentifier(id = UUID.fromString(uuid)),
                listOf(BridgeToCordappType.convert(partyAttachmentSignature))
        )).thenReturn(FlowHandleImpl<SignedTransaction>(StateMachineRunId.createRandom(), mock()))

        val handle = cordaService.signConsentBranch(uuid, partyAttachmentSignature)

        assertNotNull(handle)
    }
    @Test
    fun `finalizeConsentBranch returns FlowHandle on valid partyAttachmentSignature`() {
        val uuid = "1111-2222-33334444-5555-6666"

        `when`(cordaRPCOps.startFlow(
                ConsentFlows::MergeBranch,
                UniqueIdentifier(id = UUID.fromString(uuid))
        )).thenReturn(FlowHandleImpl<SignedTransaction>(StateMachineRunId.createRandom(), mock()))

        val handle = cordaService.mergeConsentBranch(uuid)

        assertNotNull(handle)
    }

    @Test
    fun `newConsentBranch raises on no endpoints`() {
        val newConsentBranch = newConsentBranch()

        `when`(cordaRPCOps.uploadAttachment(any())).thenReturn(SecureHash.allOnesHash)
        `when`(cordaService.endpointsApi.endpointsByOrganisationId(any(), any(), eq(false))).thenReturn(emptyArray())
        // simulate Genesis block
        `when`(cordaRPCOps.vaultQueryBy(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentState::class.java))).thenReturn(statePage(1, UniqueIdentifier()))

        assertFailsWith<IllegalArgumentException> {
            cordaService.createConsentBranch(newConsentBranch)
        }
    }

    @Test
    fun `newConsentBranch raises on missing endpoints`() {
        val newConsentBranch = newConsentBranch()
        val e = endpoint()
        val endpoint = Endpoint(
            organization = "unknown",
            identifier = e.identifier,
            endpointType = e.endpointType,
            properties = e.properties,
            status = e.status,
            URL = e.URL
        )

        `when`(cordaRPCOps.uploadAttachment(any())).thenReturn(SecureHash.allOnesHash)
        `when`(cordaService.endpointsApi.endpointsByOrganisationId(any(), any(), eq(false))).thenReturn(arrayOf(endpoint))
        // simulate Genesis block
        `when`(cordaRPCOps.vaultQueryBy(
            criteria = any(),
            paging = any(),
            sorting = any(),
            contractStateType = eq(ConsentState::class.java))).thenReturn(statePage(1, UniqueIdentifier()))

        assertFailsWith<IllegalArgumentException> {
            cordaService.createConsentBranch(newConsentBranch)
        }
    }

    @Test
    fun `newConsentBranch raises on non exact match`() {
        val newConsentBranch = newConsentBranch()

        `when`(cordaRPCOps.uploadAttachment(any())).thenReturn(SecureHash.allOnesHash)
        `when`(cordaService.endpointsApi.endpointsByOrganisationId(any(), any(), eq(false))).thenThrow(ClientException("organization with id X does not have an endpoint of type urn:nuts:endpoint:consen"))
        // simulate Genesis block
        `when`(cordaRPCOps.vaultQueryBy(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentState::class.java))).thenReturn(statePage(1, UniqueIdentifier()))

        assertFailsWith<ClientException> {
            cordaService.createConsentBranch(newConsentBranch)
        }
    }

    @Test
    fun `consentBranchByTx returns ConsentBranch given a Transaction hash`() {
        val tx : SignedTransaction = mock()
        `when`(tx.inputs).thenReturn(emptyList())
        val criteria = QueryCriteria.VaultQueryCriteria(stateRefs = emptyList(), status = Vault.StateStatus.CONSUMED)
        `when`(cordaRPCOps.internalFindVerifiedTransaction(any())).thenReturn(tx)
        `when`(cordaRPCOps.vaultQueryBy<ConsentBranch>(criteria)).thenReturn(branchPage(1))

        val branch = cordaService.consentBranchByTx(SecureHash.allOnesHash)

        assertNotNull(branch)
    }

    @Test
    fun `consentBranchByTx returns null when incorrect number branches exist for given hash`() {
        val tx : SignedTransaction = mock()
        `when`(tx.inputs).thenReturn(emptyList())
        val criteria = QueryCriteria.VaultQueryCriteria(stateRefs = emptyList(), status = Vault.StateStatus.CONSUMED)
        `when`(cordaRPCOps.internalFindVerifiedTransaction(any())).thenReturn(tx)
        `when`(cordaRPCOps.vaultQueryBy<ConsentBranch>(criteria)).thenReturn(branchPage(0))

        val branch = cordaService.consentBranchByTx(SecureHash.allOnesHash)

        assertNull(branch)
    }

    private fun endpoint() : Endpoint {
        return Endpoint(
                organization = "legalEntity",
                endpointType = "urn:nuts:endpoint:consent",
                identifier = "urn:ietf:rfc:1779:O=Nedap, OU=Healthcare, C=NL, ST=Gelderland, L=Groenlo, CN=nuts_corda_development_local",
                status = Endpoint.Status.active,
                URL = "tcp://::1:7886"
        )
    }

    private fun newConsentBranch(legalEntities: List<String>) : FullConsentRequestState {
        val att = zip(consentMetadataAsJson(), "blob".toByteArray())

        val outputStream = ByteArrayOutputStream()
        val b64 = Base64.getEncoder().wrap(outputStream)

        att.use { input ->
            b64.use { output ->
                input.copyTo(output)
            }
        }

        return FullConsentRequestState(
                consentId = ConsentId(UUID = UUID.randomUUID().toString(), externalId = "externalId"),
                consentRecords = listOf(ConsentRecord(
                        cipherText = String(outputStream.toByteArray()),
                        metadata = Metadata(
                                domain = listOf(nl.nuts.consent.bridge.model.Domain.medical),
                                period = nl.nuts.consent.bridge.model.Period(validFrom = OffsetDateTime.now()),
                                organisationSecureKeys = listOf(
                                        ASymmetricKey(
                                                legalEntity = "legalEntity",
                                                alg = "alg",
                                                cipherText = "afaf"
                                        )
                                ),
                                secureKey = nl.nuts.consent.bridge.model.SymmetricKey(alg = "alg", iv = "iv"),
                                consentRecordHash = "hash"
                        ),
                        signatures = emptyList()
                )),
                legalEntities = legalEntities
        )
    }

    private fun newConsentBranch() : FullConsentRequestState {
        return newConsentBranch(listOf("legalEntity"))
    }

    private fun consentBranch() : ConsentBranch {
        val att = zip(consentMetadataAsJson(), "blob".toByteArray())

        val outputStream = ByteArrayOutputStream()
        val b64 = Base64.getEncoder().wrap(outputStream)

        att.use { input ->
            b64.use { output ->
                input.copyTo(output)
            }
        }

        return ConsentBranch(
                uuid = UniqueIdentifier(externalId = "externalId"),
                branchPoint = UniqueIdentifier(),
                attachments = setOf(SecureHash.allOnesHash),
                legalEntities = setOf("legalEntity"),
                signatures = emptyList(),
                parties = setOf(mock())

        )
    }

    private fun zip(metadata: String?, data: ByteArray?) : InputStream {
        val baos = ByteArrayOutputStream(8096)
        val out = ZipOutputStream(baos)

        if (metadata != null) {
            val entry = ZipEntry("metadata.json")
            out.putNextEntry(entry)
            out.write(metadata.toByteArray())
        }

        if (data != null) {
            val entry = ZipEntry("data.bin")
            out.putNextEntry(entry)
            out.write(data)
        }

        out.close()
        baos.close()

        return ByteArrayInputStream(baos.toByteArray())
    }

    private fun branchPage(numberOfResults: Int) : Vault.Page<ConsentBranch> {
        val states = mutableListOf<StateAndRef<ConsentBranch>>()

        for (i in 0 until numberOfResults) {
            states.add(
                    StateAndRef(
                            state = TransactionState(
                                    ConsentBranch(
                                            uuid = UniqueIdentifier(
                                                    id = UUID.fromString("1111-2222-33334444-5555-6666")
                                            ),
                                            attachments = emptySet(),
                                            parties = emptySet(),
                                            signatures = emptyList(),
                                            legalEntities = emptySet(),
                                            branchPoint = UniqueIdentifier()
                                    ),
                                    "nl.nuts.consent.contract.ConsentContract", mock()
                            ),
                            ref = mock()
                    )
            )
        }

        return Vault.Page(
                states = states,
                otherResults = emptyList(),
                statesMetadata = emptyList(),
                stateTypes = Vault.StateStatus.UNCONSUMED,
                totalStatesAvailable = numberOfResults.toLong())
    }

    private fun statePage(numberOfResults: Int, id: UniqueIdentifier) : Vault.Page<nl.nuts.consent.state.ConsentState> {
        val states = mutableListOf<StateAndRef<nl.nuts.consent.state.ConsentState>>()

        for (i in 0 until numberOfResults) {
            states.add(
                    StateAndRef(
                            state = TransactionState(ConsentState(uuid = id, version = 1), "nl.nuts.consent.contract.ConsentContract", mock()),
                            ref = mock()
                    )
            )
        }

        return Vault.Page(
                states = states,
                otherResults = emptyList(),
                statesMetadata = emptyList(),
                stateTypes = Vault.StateStatus.UNCONSUMED,
                totalStatesAvailable = numberOfResults.toLong())
    }

    private fun consentMetadataAsJson() : String {
        return Serialization.objectMapper().writeValueAsString(consentMetadata())
    }

    private fun consentMetadata() : ConsentMetadata {
        return ConsentMetadata(
                domain = listOf(Domain.medical),
                secureKey = SymmetricKey(
                        alg = "alg",
                        iv = "iv"
                ),
                organisationSecureKeys = emptyList(),
                period = Period(
                        validFrom = OffsetDateTime.now()
                ),
                consentRecordHash = "hash"
        )
    }

    private fun consentState() : ConsentState {
        return ConsentState(
                uuid = UniqueIdentifier(externalId = "externalId"),
                parties = emptySet(),
                version = 1,
                attachments = setOf(SecureHash.allOnesHash)
        )
    }
}