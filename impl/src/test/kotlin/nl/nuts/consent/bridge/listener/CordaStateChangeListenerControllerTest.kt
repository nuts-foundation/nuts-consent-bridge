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

package nl.nuts.consent.bridge.rpc.nl.nuts.consent.bridge.listener

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.conversion.CordappToBridgeType
import nl.nuts.consent.bridge.events.apis.EventApi
import nl.nuts.consent.bridge.events.infrastructure.ClientException
import nl.nuts.consent.bridge.listener.CordaStateChangeListenerController
import nl.nuts.consent.bridge.model.*
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.NATS_CONSENT_REQUEST_SUBJECT
import nl.nuts.consent.bridge.nats.NutsEventPublisher
import nl.nuts.consent.bridge.rpc.CordaService
import nl.nuts.consent.state.ConsentBranch
import nl.nuts.consent.state.ConsentState
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.*
import nl.nuts.consent.bridge.model.Metadata as Metadata1

class CordaStateChangeListenerControllerTest {

    lateinit var cordaStateChangeListenerController: CordaStateChangeListenerController

    val cordaService: CordaService = mock()
    val eventApi: EventApi = mock()
    val nutsEventPublisher : NutsEventPublisher = mock()

    @Before
    fun setup() {
        cordaStateChangeListenerController = CordaStateChangeListenerController()

        cordaStateChangeListenerController.eventApi = eventApi
        cordaStateChangeListenerController.cordaService = cordaService
        cordaStateChangeListenerController.nutsEventPublisher = nutsEventPublisher
    }

    @Test
    fun `publishRequestStateEvent publishes new encountered event`() {
        val s = consentRequestState()
        val e = consentRequestStateToEvent(s)
        val state: TransactionState<ConsentBranch> = mock()
        `when`(state.data).thenReturn(s)
        `when`(cordaService.consentBranchToEvent(any())).thenReturn(e)
        `when`(eventApi.getEvent(s.linearId.id)).thenThrow(ClientException())

        cordaStateChangeListenerController.handleRequestStateProduced(StateAndRef(state, ref = mock()))


        verify(nutsEventPublisher).publish(eq(NATS_CONSENT_REQUEST_SUBJECT), eq(Serialization.objectMapper().writeValueAsBytes(e)))
    }

    @Test
    fun `publishRequestStateEvent reuses existing event when found`() {
        val s = consentRequestState()
        val eCheck = consentRequestStateToEvent(s)
        val eMock = consentRequestStateToEvent(s)
        eMock.payload = ""
        val state: TransactionState<ConsentBranch> = mock()
        val n = storeEvent(nl.nuts.consent.bridge.events.models.Event.Name.distributedConsentRequestReceived, s.linearId.id.toString())
        `when`(state.data).thenReturn(s)
        `when`(cordaService.consentBranchToEvent(any())).thenReturn(eMock)
        `when`(eventApi.getEvent(s.linearId.id)).thenReturn(n)

        cordaStateChangeListenerController.handleRequestStateProduced(StateAndRef(state, ref = mock()))

        // fields to copy
        eCheck.UUID = n.uuid
        eCheck.initiatorLegalEntity = n.initiatorLegalEntity
        eCheck.retryCount = n.retryCount

        // ignore for this test
        eCheck.payload = ""

        verify(nutsEventPublisher).publish(eq(NATS_CONSENT_REQUEST_SUBJECT), eq(Serialization.objectMapper().writeValueAsBytes(eCheck)))
    }

    @Test
    fun `publishStateEvent publishes new encountered event`() {
        val s = consentState()
        val n = storeEvent()
        val e = consentStateToEvent(s)
        val state: TransactionState<ConsentState> = mock()
        val ref: StateRef = mock()
        val uuid = UUID.fromString(n.uuid)
        `when`(state.data).thenReturn(s)
        `when`(ref.txhash).thenReturn(SecureHash.allOnesHash)
        `when`(cordaService.consentStateToEvent(any())).thenReturn(e)
        `when`(cordaService.consentBranchByTx(SecureHash.allOnesHash)).thenReturn(uuid)
        `when`(eventApi.getEvent(uuid)).thenReturn(n)

        cordaStateChangeListenerController.handleStateProducedEvent(StateAndRef(state, ref = ref))

        verify(nutsEventPublisher).publish(eq(NATS_CONSENT_REQUEST_SUBJECT), eq(Serialization.objectMapper().writeValueAsBytes(e)))
    }

    @Test
    fun `publishStateEvent does nothing for genesis block`() {
        val s = consentState(1)
        val state: TransactionState<ConsentState> = mock()
        `when`(state.data).thenReturn(s)

        cordaStateChangeListenerController.handleStateProducedEvent(StateAndRef(state, ref = mock()))
    }

    @Test
    fun `publishStateEvent does nothing when event in store is not found`() {
        val s = consentState()
        val e = consentStateToEvent(s)
        val state: TransactionState<ConsentState> = mock()
        `when`(state.data).thenReturn(s)
        `when`(cordaService.consentStateToEvent(any())).thenReturn(e)
        `when`(eventApi.getEvent(any())).thenThrow(ClientException())

        cordaStateChangeListenerController.handleStateProducedEvent(StateAndRef(state, ref = mock()))
    }

    private fun storeEvent(name: nl.nuts.consent.bridge.events.models.Event.Name = nl.nuts.consent.bridge.events.models.Event.Name.consentRequestConstructed,
                           uuid : String = UUID.randomUUID().toString()) : nl.nuts.consent.bridge.events.models.Event {
        return nl.nuts.consent.bridge.events.models.Event(
            uuid = uuid,
                externalId = "externalId",
                name = name,
                payload = "",
                retryCount = 0,
                initiatorLegalEntity = "legalEntity"
        )
    }

    private fun consentRequestState() : ConsentBranch {
        return ConsentBranch(
                uuid = UniqueIdentifier(externalId = "externalId"),
                branchPoint = UniqueIdentifier(),
                attachments = emptySet(),
                legalEntities = emptySet(),
                signatures = emptyList(),
                parties = emptySet()
        )
    }

    private fun consentState(version: Int = 2) : ConsentState {
        return ConsentState(
                uuid = UniqueIdentifier(externalId = "externalId"),
                version = version,
                attachments = emptySet(),
                parties = emptySet()
        )
    }

    private fun consentRequestStateToEvent(state: ConsentBranch) : Event {

        val ncrs =  FullConsentRequestState(
                consentId = ConsentId(UUID = state.linearId.id.toString() , externalId = state.linearId.externalId!!),
                legalEntities = emptyList(),
                consentRecords = listOf(ConsentRecord(
                        metadata = Metadata1(
                                domain = listOf(Domain.medical),
                                secureKey = SymmetricKey(alg = "alg", iv = "iv"),
                                organisationSecureKeys = emptyList(),
                                period = Period(OffsetDateTime.now()),
                                consentRecordHash = "hash"
                        ),
                        attachmentHash = "",
                        cipherText = "af==",
                        signatures = emptyList()
                ))
        )

        val ncrsBytes = Serialization.objectMapper().writeValueAsBytes(ncrs)
        val ncrsBase64 = Base64.getEncoder().encodeToString(ncrsBytes)

        return Event(
                UUID = ncrs.consentId.UUID,
                name = EventName.EventDistributedConsentRequestReceived,
                retryCount = 0,
                externalId = state.linearId.externalId!!,
                consentId = state.linearId.id.toString(),
                payload = ncrsBase64
        )
    }

    private fun consentStateToEvent(state: ConsentState) : Event {

        val cs = ConsentState(
                consentId = CordappToBridgeType.convert(state.linearId),
                consentRecords = listOf(
                        ConsentRecord(
                                metadata = Metadata1(
                                        domain = listOf(Domain.medical),
                                        secureKey = SymmetricKey(alg = "alg", iv = "iv"),
                                        organisationSecureKeys = emptyList(),
                                        period = Period(OffsetDateTime.now()),
                                        consentRecordHash = "hash"
                                ),
                                cipherText = "af==",
                                signatures = emptyList()
                        )
                )
        )

        val csBytes = Serialization.objectMapper().writeValueAsBytes(cs)
        val csBase64 = Base64.getEncoder().encodeToString(csBytes)

        return Event(
                UUID = UUID.randomUUID().toString(),
                name = EventName.EventConsentDistributed,
                retryCount = 0,
                externalId = state.linearId.externalId!!,
                consentId = state.linearId.id.toString(),
                payload = csBase64
        )
    }
}