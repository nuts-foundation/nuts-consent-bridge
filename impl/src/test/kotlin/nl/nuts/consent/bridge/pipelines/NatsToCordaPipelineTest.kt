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

package nl.nuts.consent.bridge.pipelines

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.verifyZeroInteractions
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.StateMachineRunId
import net.corda.core.messaging.FlowHandle
import net.corda.core.transactions.SignedTransaction
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.StateFileStorageControl
import nl.nuts.consent.bridge.api.NotFoundException
import nl.nuts.consent.bridge.corda.CordaManagedConnection
import nl.nuts.consent.bridge.corda.CordaService
import nl.nuts.consent.bridge.model.ConsentId
import nl.nuts.consent.bridge.model.ConsentRecord
import nl.nuts.consent.bridge.model.Domain
import nl.nuts.consent.bridge.model.FullConsentRequestState
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.model.Period
import nl.nuts.consent.bridge.model.SignatureWithKey
import nl.nuts.consent.bridge.model.SymmetricKey
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.EventStateStore
import nl.nuts.consent.bridge.nats.NATS_CONSENT_REQUEST_SUBJECT
import nl.nuts.consent.bridge.nats.NatsManagedConnection
import nl.nuts.consent.contract.AttachmentSignature
import nl.nuts.consent.state.ConsentBranch
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull


class NatsToCordaPipelineTest {

    lateinit var natsToCordaPipeline: NatsToCordaPipeline
    lateinit var cordaService: CordaService
    lateinit var publisherConnection: NatsManagedConnection
    lateinit var natsManagedConnection: NatsManagedConnection
    lateinit var cordaManagedConnection: CordaManagedConnection
    lateinit var stateFileStorageControl: StateFileStorageControl

    @Before
    fun setup() {
        cordaService = mock()
        publisherConnection = mock()
        natsManagedConnection = mock()
        cordaManagedConnection = mock()
        stateFileStorageControl = mock()
        natsToCordaPipeline = initNewListener()

        `when`(publisherConnection.getConnection()).thenReturn(mock())
        `when`(natsManagedConnection.getConnection()).thenReturn(mock())
    }

    private fun initNewListener() : NatsToCordaPipeline {
        val natsToCordaPipeline = NatsToCordaPipeline()
        natsToCordaPipeline.cordaService = cordaService
        natsToCordaPipeline.eventStateStore = EventStateStore()
        natsToCordaPipeline.natsManagedConnection = mock()
        natsToCordaPipeline.publisherConnection = publisherConnection
        natsToCordaPipeline.cordaManagedConnection = cordaManagedConnection
        natsToCordaPipeline.stateFileStorageControl = stateFileStorageControl

        return natsToCordaPipeline
    }

    @Test
    fun `events are ignored when for other modules`() {
        //when
        natsToCordaPipeline.processEvent(event(EventName.EventCompleted))

        // then
        verifyZeroInteractions(natsManagedConnection, cordaManagedConnection, cordaService)
    }

    @Test
    fun `timestamp is updated`() {
        //when
        natsToCordaPipeline.processEvent(event(EventName.EventCompleted))

        // then
        verify(stateFileStorageControl).writeTimestamp(eq(NATS_CONSENT_REQUEST_SUBJECT), any())
    }

    @Test
    fun `requested state is forwarded to consentService`() {
        val t: FlowHandle<SignedTransaction> = mock()
        val st: StateMachineRunId = mock()
        `when`(t.id).thenReturn(st)
        `when`(st.uuid).thenReturn(UUID.randomUUID())

        //when
        `when`(cordaService.createConsentBranch(any())).thenReturn(t)
        `when`(cordaService.consentBranchExists(any())).thenReturn(false)
        natsToCordaPipeline.processEvent(newConsentRequestStateAsEvent())

        // then
        verify(cordaService).createConsentBranch(any())
    }

    @Test
    fun `requested state is ignored when branch exists`() {
        val t: FlowHandle<SignedTransaction> = mock()
        val st: StateMachineRunId = mock()
        `when`(t.id).thenReturn(st)
        `when`(st.uuid).thenReturn(UUID.randomUUID())

        //when
        `when`(cordaService.consentBranchExists(any())).thenReturn(true)
        natsToCordaPipeline.processEvent(newConsentRequestStateAsEvent())

        // then
        verify(cordaService, never()).createConsentBranch(any())
    }

    @Test
    fun `sign event is ignored when branch does not exist`() {
        val t: FlowHandle<SignedTransaction> = mock()
        val st: StateMachineRunId = mock()
        `when`(t.id).thenReturn(st)
        `when`(st.uuid).thenReturn(UUID.randomUUID())

        //when
        `when`(cordaService.consentBranchByUUID(any())).thenThrow(NotFoundException(""))
        natsToCordaPipeline.processEvent(acceptConsentRequestAsEvent())

        // then
        verify(cordaService, never()).signConsentBranch(any(), any())
    }

    @Test
    fun `sign event is ignored when branch already has signature`() {
        val t: FlowHandle<SignedTransaction> = mock()
        val st: StateMachineRunId = mock()
        `when`(t.id).thenReturn(st)
        `when`(st.uuid).thenReturn(UUID.randomUUID())

        //when
        `when`(cordaService.consentBranchByUUID(any())).thenReturn(
                ConsentBranch(
                        signatures = listOf(
                                AttachmentSignature(
                                        legalEntityURI = "custodian",
                                        attachmentHash = SecureHash.zeroHash,
                                        signature = mock()
                                )
                        ),
                        uuid = mock(),
                        attachments = emptySet(),
                        branchPoint = mock(),
                        legalEntities = emptySet()
                )
        )
        natsToCordaPipeline.processEvent(acceptConsentRequestAsEvent())

        // then
        verify(cordaService, never()).signConsentBranch(any(), any())
    }

    @Test
    fun `sign event is forwarded to corda`() {
        val t: FlowHandle<SignedTransaction> = mock()
        val st: StateMachineRunId = mock()
        `when`(t.id).thenReturn(st)
        `when`(st.uuid).thenReturn(UUID.randomUUID())

        //when
        `when`(cordaService.consentBranchByUUID(any())).thenReturn(
                ConsentBranch(
                        signatures = emptyList(),
                        uuid = mock(),
                        attachments = emptySet(),
                        branchPoint = mock(),
                        legalEntities = emptySet()
                )
        )
        `when`(cordaService.signConsentBranch(any(), any())).thenReturn(t)
        natsToCordaPipeline.processEvent(acceptConsentRequestAsEvent())

        // then
        verify(cordaService).signConsentBranch(any(), any())
    }

    @Test
    fun `consentRequestInFlight event is put in store when unknown`() {
        val uuid = UUID.randomUUID()
        val event = event(EventName.EventConsentRequestInFlight, uuid.toString())

        assertNull(natsToCordaPipeline.eventStateStore.get(uuid))

        natsToCordaPipeline.processEvent(event)

        assertNotNull(natsToCordaPipeline.eventStateStore.get(uuid))
    }

    @Test
    fun `EventInFinalFlight event is ignored when known`() {
        val uuid = UUID.randomUUID()
        val knownEvent = event(EventName.EventCompleted, uuid.toString())
        val event = event(EventName.EventInFinalFlight, uuid.toString())

        natsToCordaPipeline.eventStateStore.put(uuid, knownEvent)

        natsToCordaPipeline.processEvent(event)

        assertEquals(EventName.EventCompleted, natsToCordaPipeline.eventStateStore.get(uuid)?.name)
    }

    @Test
    fun `allSignaturesPresent is ignored when not initiator`() {
        val event = event(EventName.EventAllSignaturesPresent).copy(initiatorLegalEntity = null)

        natsToCordaPipeline.processEvent(event)

        verifyZeroInteractions(cordaService)
    }

    @Test
    fun `allSignaturesPresent raises when consentId is null`() {
        val event = event(EventName.EventAllSignaturesPresent).copy(consentId = null)

        assertFailsWith(IllegalStateException::class) {
            natsToCordaPipeline.processEvent(event)
        }
    }

    @Test
    fun `allSignaturesPresent calls mergeConsentBranch`() {
        val uuid = UUID.randomUUID()
        val event = event(EventName.EventAllSignaturesPresent, uuid.toString())
        val t: FlowHandle<SignedTransaction> = mock()
        val st: StateMachineRunId = mock()
        `when`(t.id).thenReturn(st)
        `when`(st.uuid).thenReturn(uuid)
        `when`(cordaService.mergeConsentBranch(any())).thenReturn(t)

        natsToCordaPipeline.processEvent(event)

        assertEquals(EventName.EventInFinalFlight, natsToCordaPipeline.eventStateStore.get(uuid)?.name)
    }

    private fun event(name : EventName) : Event {
        return event(name, UUID.randomUUID().toString())
    }

    private fun event(name : EventName, uuid: String) : Event {
        return Event(
            UUID = uuid,
            name = name,
            retryCount = 0,
            externalId = "uuid",
            initiatorLegalEntity = "custodian",
            payload = "",
            consentId = "consentUuid",
            error = null,
            transactionId = uuid
        )
    }

    private fun newConsentRequestStateAsEvent(): Event {
        val newConsentRequestState = FullConsentRequestState(
            consentId = ConsentId(UUID = UUID.randomUUID().toString(), externalId = "externalId"),
            legalEntities = emptyList(),
            consentRecords = listOf(ConsentRecord(
                cipherText = "",
                metadata = nl.nuts.consent.bridge.model.Metadata(
                    domain = listOf(Domain.medical),
                    period = Period(validFrom = OffsetDateTime.now()),
                    organisationSecureKeys = emptyList(),
                    secureKey = SymmetricKey(alg = "alg", iv = "iv"),
                    consentRecordHash = "hash"
                ),
                attachmentHash = "",
                signatures = emptyList()
            )),
            initiatingLegalEntity = ""
        )
        val emptyJson = Serialization.objectMapper().writeValueAsString(newConsentRequestState)

        return Event(
            UUID = newConsentRequestState.consentId.UUID,
            name = EventName.EventConsentRequestConstructed,
            retryCount = 0,
            externalId = "uuid",
            initiatorLegalEntity = "custodian",
            payload = Base64.getEncoder().encodeToString(emptyJson.toByteArray()),
            consentId = "consentUuid",
            error = null
        )
    }


    private fun acceptConsentRequestAsEvent() : Event {
        val partyAttachmentSignature = PartyAttachmentSignature(
                legalEntity = "custodian",
                attachment = "",
                signature = SignatureWithKey(
                        data = "",
                        publicKey = emptyMap()
                )
        )
        val emptyJson = Serialization.objectMapper().writeValueAsString(partyAttachmentSignature)

        return Event(
            UUID = "1111-2222-33334444-5555-6666",
            name = EventName.EventAttachmentSigned,
            retryCount = 0,
            externalId = "uuid",
            initiatorLegalEntity = "custodian",
            payload = Base64.getEncoder().encodeToString(emptyJson.toByteArray()),
            consentId = "consentUuid",
            error = null
        )
    }
}