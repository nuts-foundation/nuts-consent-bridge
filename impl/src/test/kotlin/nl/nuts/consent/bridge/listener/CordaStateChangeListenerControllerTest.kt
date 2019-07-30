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
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.UniqueIdentifier
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.conversion.CordappToBridgeType
import nl.nuts.consent.bridge.events.apis.EventApi
import nl.nuts.consent.bridge.events.infrastructure.ClientException
import nl.nuts.consent.bridge.listener.CordaStateChangeListenerController
import nl.nuts.consent.bridge.model.Domain
import nl.nuts.consent.bridge.model.NewConsentRequestState
import nl.nuts.consent.bridge.model.Period
import nl.nuts.consent.bridge.model.SymmetricKey
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.NutsEventPublisher
import nl.nuts.consent.bridge.rpc.CordaService
import nl.nuts.consent.state.ConsentRequestState
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
        val state: TransactionState<ConsentRequestState> = mock()
        `when`(state.data).thenReturn(s)
        `when`(cordaService.consentRequestStateToEvent(any())).thenReturn(e)
        `when`(eventApi.getEventByExternalId("externalId")).thenThrow(ClientException())

        cordaStateChangeListenerController.handleRequestStateProduced(StateAndRef(state, ref = mock()))


        verify(nutsEventPublisher).publish(eq("consentRequest"), eq(Serialization.objectMapper().writeValueAsBytes(e)))
    }

    @Test
    fun `publishRequestStateEvent reuses existing event when found`() {
        val s = consentRequestState()
        val e = consentRequestStateToEvent(s)
        val state: TransactionState<ConsentRequestState> = mock()
        val n = storeEvent()
        `when`(state.data).thenReturn(s)
        `when`(cordaService.consentRequestStateToEvent(any())).thenReturn(e)
        `when`(eventApi.getEventByExternalId("externalId")).thenReturn(n)

        cordaStateChangeListenerController.handleRequestStateProduced(StateAndRef(state, ref = mock()))

        e.UUID = n.uuid.toString()

        verify(nutsEventPublisher).publish(eq("consentRequest"), eq(Serialization.objectMapper().writeValueAsBytes(e)))
    }

    @Test
    fun `publishStateEvent publishes new encountered event`() {
        val s = consentState()
        val e = consentStateToEvent(s)
        val state: TransactionState<ConsentState> = mock()
        `when`(state.data).thenReturn(s)
        `when`(cordaService.consentStateToEvent(any())).thenReturn(e)
        `when`(eventApi.getEventByExternalId("externalId")).thenThrow(ClientException())

        cordaStateChangeListenerController.handleStateProducedEvent(StateAndRef(state, ref = mock()))

        verify(nutsEventPublisher).publish(eq("consentRequest"), eq(Serialization.objectMapper().writeValueAsBytes(e)))
    }

    @Test
    fun `publishStateEvent reuses existing event when found`() {
        val s = consentState()
        val e = consentStateToEvent(s)
        val state: TransactionState<ConsentState> = mock()
        val n = storeEvent()
        `when`(state.data).thenReturn(s)
        `when`(cordaService.consentStateToEvent(any())).thenReturn(e)
        `when`(eventApi.getEventByExternalId("externalId")).thenReturn(n)

        cordaStateChangeListenerController.handleStateProducedEvent(StateAndRef(state, ref = mock()))

        e.UUID = n.uuid.toString()

        verify(nutsEventPublisher).publish(eq("consentRequest"), eq(Serialization.objectMapper().writeValueAsBytes(e)))
    }

    private fun storeEvent() : nl.nuts.consent.bridge.events.models.Event {
        return nl.nuts.consent.bridge.events.models.Event(
            uuid = UUID.randomUUID(),
                externalId = "externalId",
                name = nl.nuts.consent.bridge.events.models.Event.Name.consentRequestConstructed,
                payload = "",
                retryCount = 0
        )
    }

    private fun consentRequestState() : ConsentRequestState {
        return ConsentRequestState(
                externalId = "externalId",
                attachments = emptySet(),
                legalEntities = emptySet(),
                signatures = emptyList(),
                parties = emptySet()
        )
    }

    private fun consentState() : ConsentState {
        return ConsentState(
                consentStateUUID = UniqueIdentifier(externalId = "externalId"),
                attachments = emptySet(),
                parties = emptySet()
        )
    }

    private fun consentRequestStateToEvent(state: ConsentRequestState) : Event {

        val ncrs =  NewConsentRequestState(
                externalId = state.consentStateUUID.externalId!!,
                metadata = Metadata1(
                    domain = listOf(Domain.medical),
                        secureKey = SymmetricKey(alg = "alg", iv = "iv"),
                        organisationSecureKeys = emptyList(),
                        period = Period(OffsetDateTime.now())
                ),
                attachment = "af=="
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

    private fun consentStateToEvent(state: ConsentState) : Event {

        val cs =  nl.nuts.consent.bridge.model.ConsentState(
                consentId = CordappToBridgeType.convert(state.consentStateUUID),
                metadata = Metadata1(
                        domain = listOf(Domain.medical),
                        secureKey = SymmetricKey(alg = "alg", iv = "iv"),
                        organisationSecureKeys = emptyList(),
                        period = Period(OffsetDateTime.now())
                ),
                cipherText = "af=="
        )

        val csBytes = Serialization.objectMapper().writeValueAsBytes(cs)
        val csBase64 = Base64.getEncoder().encodeToString(csBytes)

        return Event(
                UUID = UUID.randomUUID().toString(),
                name = EventName.EventConsentDistributed,
                retryCount = 0,
                externalId = state.consentStateUUID.externalId!!,
                consentId = state.consentStateUUID.id.toString(),
                payload = csBase64
        )
    }
}