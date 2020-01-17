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

package nl.nuts.consent.bridge.nats

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.verify
import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.StateMachineRunId
import net.corda.core.messaging.FlowHandle
import net.corda.core.transactions.SignedTransaction
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.api.NotFoundException
import nl.nuts.consent.bridge.model.*
import nl.nuts.consent.bridge.rpc.CordaService
import nl.nuts.consent.contract.AttachmentSignature
import nl.nuts.consent.state.ConsentBranch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.springframework.test.web.client.ExpectedCount.once
import java.time.OffsetDateTime
import java.util.*

class NutsEventListenerTest {

    lateinit var cf : StreamingConnectionFactory
    lateinit var connection: StreamingConnection
    lateinit var nutsEventListener: NutsEventListener

    lateinit var cordaService: CordaService

    @Before
    fun setup() {
        cf = StreamingConnectionFactory("test-cluster", "cordaBridgeTest-${Integer.toHexString(Random().nextInt())}")

        cordaService = mock()

        nutsEventListener = initNewListener()
        cf.natsUrl = nutsEventListener.consentBridgeNatsProperties.address
        connection = cf.createConnection()

        // wait for connection to be established
        val t = System.currentTimeMillis()
        while ((System.currentTimeMillis() - t) > 2000L) {
            val connected = nutsEventListener.connected()
            if (connected) {
                break
            }
            Thread.sleep(10L)
        }
    }

    @After
    fun tearDown() {
        connection.close()
        nutsEventListener.destroy()
        nutsEventListener.destroyBase()
    }

    private fun initNewListener() : NutsEventListener {
        val nutsEventListener = NutsEventListener()
        nutsEventListener.consentBridgeNatsProperties = ConsentBridgeNatsProperties()
        nutsEventListener.cordaService = cordaService
        nutsEventListener.eventStateStore = EventStateStore()
        nutsEventListener.nutsEventPublisher = mock()
        nutsEventListener.init()

        return nutsEventListener
    }

    @Test
    fun `events are ignored when for other modules`() {
        //when
        val e = Serialization.objectMapper().writeValueAsBytes(event(EventName.EventCompleted))
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, e)

        // then
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

        val e = Serialization.objectMapper().writeValueAsBytes(newConsentRequestStateAsEvent())
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, e)

        Thread.sleep(1000)

        // then
        verify(cordaService).createConsentBranch(any())
    }

    @Test
    fun `events survive shutdowns`() {
        val t: FlowHandle<SignedTransaction> = mock()
        val st: StateMachineRunId = mock()
        `when`(t.id).thenReturn(st)
        `when`(st.uuid).thenReturn(UUID.randomUUID())

        //when
        `when`(cordaService.createConsentBranch(any())).thenReturn(t)
        `when`(cordaService.consentBranchExists(any())).thenReturn(false)
        val e = Serialization.objectMapper().writeValueAsBytes(newConsentRequestStateAsEvent())

        // close down listener
        nutsEventListener.destroy()
        nutsEventListener.destroyBase()

        // publish
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, e)

        // restart listener
        nutsEventListener = initNewListener()

        Thread.sleep(1000)

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

        val e = Serialization.objectMapper().writeValueAsBytes(newConsentRequestStateAsEvent())
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, e)

        Thread.sleep(1000)

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

        val e = Serialization.objectMapper().writeValueAsBytes(acceptConsentRequestAsEvent())
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, e)

        Thread.sleep(1000)

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

        val e = Serialization.objectMapper().writeValueAsBytes(acceptConsentRequestAsEvent())
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, e)

        Thread.sleep(1000)

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

        val e = Serialization.objectMapper().writeValueAsBytes(acceptConsentRequestAsEvent())
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, e)

        Thread.sleep(1000)

        // then
        verify(cordaService).signConsentBranch(any(), any())
    }

    private fun event(name : EventName) : Event {
        return Event(
                UUID = "1111-2222-33334444-5555-6666",
                name = name,
                retryCount = 0,
                externalId = "uuid",
                initiatorLegalEntity = "custodian",
                payload = "",
                consentId = "consentUuid",
                error = null
        )
    }

    private fun newConsentRequestStateAsEvent() : Event {
        val newConsentRequestState = FullConsentRequestState(
                consentId = ConsentId(UUID = UUID.randomUUID().toString(),externalId = "externalId"),
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
                ))
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