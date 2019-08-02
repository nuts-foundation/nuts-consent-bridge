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

import com.fasterxml.jackson.module.kotlin.readValue
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.FlowHandleImpl
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.Vault
import net.corda.core.transactions.SignedTransaction
import net.corda.nodeapi.exceptions.DuplicateAttachmentException
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.api.NotFoundException
import nl.nuts.consent.bridge.conversion.BridgeToCordappType
import nl.nuts.consent.bridge.model.*
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.registry.models.Endpoint
import nl.nuts.consent.flow.ConsentRequestFlows
import nl.nuts.consent.model.ConsentMetadata
import nl.nuts.consent.model.Domain
import nl.nuts.consent.model.Period
import nl.nuts.consent.model.SymmetricKey
import nl.nuts.consent.state.ConsentRequestState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith

class CordaServiceTest {

    val cordaRPCOps: CordaRPCOps = mock()
    val cordaRPClientWrapper: CordaRPClientWrapper = mock {
        on(it.proxy()) doReturn cordaRPCOps
    }

    val VALID_HEX = "afafafafafafafafafafafafafafafafafafafafafafafafafafafafafafafaf"

    lateinit var cordaService : CordaService

    @Before
    fun setup() {
        cordaService = CordaService()
        cordaService.cordaRPClientWrapper = cordaRPClientWrapper
        cordaService.endpointsApi = mock()
    }

    @Test
    fun `consentRequestStateByUUID throws NotFoundException when proxy returns empty states`() {
        `when`(cordaRPCOps.vaultQueryBy<ConsentRequestState>(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentRequestState::class.java))).thenReturn(page(0))

        try {
            cordaService.consentRequestStateByUUID("1111-2222-33334444-5555-6666")
            fail("Exception should have been raised")
        } catch (e: NotFoundException) {
            // suc6
        }
    }

    @Test
    fun `consentRequestStateByUUID throws IllegalStateException when proxy returns more than 1 state`() {
        `when`(cordaRPCOps.vaultQueryBy<ConsentRequestState>(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentRequestState::class.java))).thenReturn(page(2))

        try {
            cordaService.consentRequestStateByUUID("1111-2222-33334444-5555-6666")
            fail("Exception should have been raised")
        } catch (e: IllegalStateException) {
            // suc6
        }
    }

    @Test
    fun `consentRequestStateByUUID state data on success`() {
        `when`(cordaRPCOps.vaultQueryBy<ConsentRequestState>(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentRequestState::class.java))).thenReturn(page(1))

        val state = cordaService.consentRequestStateByUUID("1111-2222-33334444-5555-6666")
        assertNotNull(state)
    }

    @Test
    fun `getAttachment returns null for unknown attachment`() {
        val hash = SecureHash.parse(VALID_HEX)

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(false)

        val att = cordaService.getAttachment(hash)

        assertNull(att)
    }

    @Test
    fun `getAttachment returns correct data for correct attachment`(){
        val hash = SecureHash.parse(VALID_HEX)

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(hash)).thenReturn(zip(consentMetadataAsJson(), "blob"))

        val att = cordaService.getAttachment(hash)

        assertNotNull(att)
        assertTrue(att!!.metadata.domain.contains(Domain.medical))
        assertEquals("YmxvYg==", Base64.getEncoder().encodeToString(att.data))
    }

    @Test
    fun `getAttachment throws IllegalState for missing metadata`(){
        val hash = SecureHash.parse(VALID_HEX)

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(hash)).thenReturn(zip(null, "blob"))


        assertFailsWith<IllegalStateException> {
            cordaService.getAttachment(hash)
        }
    }

    @Test
    fun `getAttachment throws IllegalState for missing binary`(){
        val hash = SecureHash.parse(VALID_HEX)

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(hash)).thenReturn(zip(consentMetadataAsJson(), null))


        assertFailsWith<IllegalStateException> {
            cordaService.getAttachment(hash)
        }
    }

    @Test
    fun `contractToStateEvent returns event for valid data`() {
        val consentRequestState = consentRequestState()

        `when`(cordaRPCOps.attachmentExists(SecureHash.allOnesHash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(SecureHash.allOnesHash)).thenReturn(zip(consentMetadataAsJson(), "blob"))

        val event = cordaService.consentRequestStateToEvent(consentRequestState())

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
        val consentRequestState = consentRequestState()

        `when`(cordaRPCOps.attachmentExists(SecureHash.allOnesHash)).thenReturn(false)

        assertFailsWith<IllegalStateException> {
            cordaService.consentRequestStateToEvent(consentRequestState())
        }
    }

    @Test
    fun `newConsentRequestState returns FlowHandle on valid NewConsentRequestState`() {
        val newConsentRequestState = newConsentRequestState()

        `when`(cordaRPCOps.uploadAttachment(any())).thenReturn(SecureHash.allOnesHash)
        `when`(cordaService.endpointsApi.endpointsByOrganisationId(any(), eq("urn:nuts:endpoint:consent"))).thenReturn(arrayOf(endpoint()))
        `when`(cordaRPCOps.startFlow(
                ConsentRequestFlows::NewConsentRequest,
                "externalId",
                setOf(SecureHash.allOnesHash),
                setOf("legalEntity"),
                setOf(CordaX500Name.parse("O=Nedap, OU=Healthcare, C=NL, ST=Gelderland, L=Groenlo, CN=nuts_corda_development_local"))
        )).thenReturn(FlowHandleImpl<SignedTransaction>(StateMachineRunId.createRandom(), mock()))

        val handle = cordaService.newConsentRequestState(newConsentRequestState)

        assertNotNull(handle)
    }

    @Test
    fun `newConsentRequestState returns FlowHandle on valid NewConsentRequestState with duplicate attachment upload`() {
        val newConsentRequestState = newConsentRequestState()

        `when`(cordaRPCOps.uploadAttachment(any())).thenThrow(DuplicateAttachmentException(SecureHash.allOnesHash.toString()))
        `when`(cordaService.endpointsApi.endpointsByOrganisationId(any(), eq("urn:nuts:endpoint:consent"))).thenReturn(arrayOf(endpoint()))
        `when`(cordaRPCOps.startFlow(
                ConsentRequestFlows::NewConsentRequest,
                "externalId",
                setOf(SecureHash.allOnesHash),
                setOf("legalEntity"),
                setOf(CordaX500Name.parse("O=Nedap, OU=Healthcare, C=NL, ST=Gelderland, L=Groenlo, CN=nuts_corda_development_local"))
        )).thenReturn(FlowHandleImpl<SignedTransaction>(StateMachineRunId.createRandom(), mock()))

        val handle = cordaService.newConsentRequestState(newConsentRequestState)

        assertNotNull(handle)
    }


    @Test
    fun `acceptConsentRequestState returns FlowHandle on valid partyAttachmentSignature`() {
        val uuid = "1111-2222-33334444-5555-6666"
        val attachment = VALID_HEX
        val partyAttachmentSignature = PartyAttachmentSignature(
                legalEntity = "legalEntity",
                attachment = attachment,
                signature = SignatureWithKey(
                        publicKey = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwm7FBfggHaAfapO7TdFv\n0OwS+Ip9Wi7gyhddjmdZBZDzfYMUPr4+0utGM3Ry8JtCfxmsHL3ZmvG04GV1doeC\nLjLywm6OFfoEQCpliRiCyarpd2MrxKWjkSwOl9MJdVm3xpb7BWJdXkKEwoU4lBk8\ncZPay32juPzAV5eb6UCnq53PZ5O0H80J02oPLpBs2D6ASjUQpRf2xP0bvaP2W92P\nZYzJwrSA3zdxPmrMVApOoIZL7OHBE+y0I9ZUt+zmxD8TzRdN9Etf9wjLD7psu9aL\n/XHIHR0xMkYV8cr/nCbJ6H0PbDd3yIQvYPjLEVS5LeieN+DzIlYO6Y7kpws6k0rx\newIDAQAB\n-----END PUBLIC KEY-----\n",
                        data = "afaf"
                )
        )

        `when`(cordaRPCOps.startFlow(
                ConsentRequestFlows::AcceptConsentRequest,
                UniqueIdentifier(id = UUID.fromString(uuid)),
                listOf(BridgeToCordappType.convert(partyAttachmentSignature))
        )).thenReturn(FlowHandleImpl<SignedTransaction>(StateMachineRunId.createRandom(), mock()))

        val handle = cordaService.acceptConsentRequestState(uuid, partyAttachmentSignature)

        assertNotNull(handle)
    }
    @Test
    fun `finalizeConsentRequestState returns FlowHandle on valid partyAttachmentSignature`() {
        val uuid = "1111-2222-33334444-5555-6666"

        `when`(cordaRPCOps.startFlow(
                ConsentRequestFlows::FinalizeConsentRequest,
                UniqueIdentifier(id = UUID.fromString(uuid))
        )).thenReturn(FlowHandleImpl<SignedTransaction>(StateMachineRunId.createRandom(), mock()))

        val handle = cordaService.finalizeConsentRequestState(uuid)

        assertNotNull(handle)
    }

    @Test
    fun `newConsentRequestState raises on missing endpoints`() {
        val newConsentRequestState = newConsentRequestState()

        `when`(cordaRPCOps.uploadAttachment(any())).thenReturn(SecureHash.allOnesHash)
        `when`(cordaService.endpointsApi.endpointsByOrganisationId(any(), eq("urn:nuts:endpoint:consent"))).thenReturn(emptyArray())

        assertFailsWith<IllegalArgumentException> {
            cordaService.newConsentRequestState(newConsentRequestState)
        }
    }

    private fun endpoint() : Endpoint {
        return Endpoint(
                endpointType = "urn:nuts:endpoint:consent",
                identifier = "urn:ietf:rfc:1779:O=Nedap, OU=Healthcare, C=NL, ST=Gelderland, L=Groenlo, CN=nuts_corda_development_local",
                status = Endpoint.Status.active,
                version = "1.0",
                URL = "tcp://::1:7886"
        )
    }

    private fun newConsentRequestState() : FullConsentRequestState {
        val att = zip(consentMetadataAsJson(), "blob")

        val outputStream = ByteArrayOutputStream()
        val b64 = Base64.getEncoder().wrap(outputStream)

        att.use { input ->
            b64.use { output ->
                input.copyTo(output)
            }
        }

        return FullConsentRequestState(
                consentId = ConsentId(externalId = "externalId"),
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
                        secureKey = nl.nuts.consent.bridge.model.SymmetricKey(alg = "alg", iv = "iv")
                ),
                legalEntities = emptyList()
        )
    }

    private fun consentRequestState() : ConsentRequestState {
        val att = zip(consentMetadataAsJson(), "blob")

        val outputStream = ByteArrayOutputStream()
        val b64 = Base64.getEncoder().wrap(outputStream)

        att.use { input ->
            b64.use { output ->
                input.copyTo(output)
            }
        }

        return ConsentRequestState(
                externalId = "externalId",
                attachments = setOf(SecureHash.allOnesHash),
                legalEntities = setOf("legalEntity"),
                signatures = emptyList(),
                parties = setOf(mock())

        )
    }

    private fun zip(metadata: String?, data: String?) : InputStream {
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
            out.write(data.toByteArray())
        }

        out.close()
        baos.close()

        return ByteArrayInputStream(baos.toByteArray())
    }

    private fun page(numberOfResults: Int) : Vault.Page<ConsentRequestState> {
        val states = mutableListOf<StateAndRef<ConsentRequestState>>()

        for (i in 0 until numberOfResults) {
            states.add(
                    StateAndRef(
                            state = TransactionState(mock(), "nl.nuts.consent.contract.ConsentContract", mock()),
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
                        validFrom = LocalDate.now()
                )
        )
    }
}