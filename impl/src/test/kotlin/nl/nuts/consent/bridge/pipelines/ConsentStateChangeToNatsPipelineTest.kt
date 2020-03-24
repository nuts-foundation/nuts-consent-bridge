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
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.verifyZeroInteractions
import io.nats.streaming.StreamingConnection
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.conversion.CordappToBridgeType
import nl.nuts.consent.bridge.corda.CordaService
import nl.nuts.consent.bridge.events.apis.EventApi
import nl.nuts.consent.bridge.events.infrastructure.ClientException
import nl.nuts.consent.bridge.model.ConsentRecord
import nl.nuts.consent.bridge.model.ConsentState
import nl.nuts.consent.bridge.model.Domain
import nl.nuts.consent.bridge.model.Period
import nl.nuts.consent.bridge.model.SymmetricKey
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.NATS_CONSENT_REQUEST_SUBJECT
import nl.nuts.consent.bridge.nats.NatsManagedConnection
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.UUID
import java.util.Base64
import nl.nuts.consent.bridge.model.Metadata as Metadata1

class ConsentStateChangeToNatsPipelineTest {

    lateinit var consentStateChangeToCordaPipeline: ConsentStateChangeToNatsPipeline

    val cordaService: CordaService = mock()
    val eventApi: EventApi = mock()
    val streamingConnection: StreamingConnection = mock()
    val natsManagedConnection: NatsManagedConnection = mock()

    @Before
    fun setup() {
        `when`(natsManagedConnection.getConnection()).thenReturn(streamingConnection)

        consentStateChangeToCordaPipeline = ConsentStateChangeToNatsPipeline()

        consentStateChangeToCordaPipeline.cordaService = cordaService
        consentStateChangeToCordaPipeline.natsManagedConnection = natsManagedConnection
        consentStateChangeToCordaPipeline.eventApi = eventApi
    }

    private fun verifyPublishedEvent(event: Event) {
        verify(streamingConnection).publish(eq(NATS_CONSENT_REQUEST_SUBJECT), eq(Serialization.objectMapper().writeValueAsBytes(event)), any())
    }

    @Test
    fun `publishStateEvent publishes new encountered event`() {
        // given
        val s = consentState()
        val n = storeEvent()
        val e = consentStateToEvent(s)
        val state: TransactionState<nl.nuts.consent.state.ConsentState> = mock()
        val ref: StateRef = mock()
        val uuid = UUID.fromString(n.uuid)
        `when`(state.data).thenReturn(s)
        `when`(ref.txhash).thenReturn(SecureHash.allOnesHash)
        `when`(cordaService.consentStateToEvent(any())).thenReturn(e)
        `when`(cordaService.consentBranchByTx(SecureHash.allOnesHash)).thenReturn(uuid)
        `when`(eventApi.getEvent(uuid)).thenReturn(n)

        // when
        consentStateChangeToCordaPipeline.stateProduced(StateAndRef(state, ref = ref))

        // then
        verifyPublishedEvent(e)
    }

    @Test
    fun `publishStateEvent does nothing for genesis block`() {
        // then
        val s = consentState(1)
        val state: TransactionState<nl.nuts.consent.state.ConsentState> = mock()
        `when`(state.data).thenReturn(s)

        // when
        consentStateChangeToCordaPipeline.stateProduced(StateAndRef(state, ref = mock()))

        // then
        verifyZeroInteractions(streamingConnection)
    }

    @Test
    fun `publishStateEvent does nothing when event in store is not found`() {
        // then
        val s = consentState()
        val e = consentStateToEvent(s)
        val state: TransactionState<nl.nuts.consent.state.ConsentState> = mock()
        `when`(state.data).thenReturn(s)
        `when`(cordaService.consentStateToEvent(any())).thenReturn(e)
        `when`(eventApi.getEvent(any())).thenThrow(ClientException())

        // when
        consentStateChangeToCordaPipeline.stateProduced(StateAndRef(state, ref = mock()))

        // then
        verifyZeroInteractions(streamingConnection)
    }

    private fun storeEvent(name: nl.nuts.consent.bridge.events.models.Event.Name = nl.nuts.consent.bridge.events.models.Event.Name.consentRequestConstructed,
                           uuid: String = UUID.randomUUID().toString()): nl.nuts.consent.bridge.events.models.Event {
        return nl.nuts.consent.bridge.events.models.Event(
            uuid = uuid,
            externalId = "externalId",
            name = name,
            payload = "",
            retryCount = 0,
            initiatorLegalEntity = "legalEntity"
        )
    }

    private fun consentState(version: Int = 2): nl.nuts.consent.state.ConsentState {
        return nl.nuts.consent.state.ConsentState(
            uuid = UniqueIdentifier(externalId = "externalId"),
            version = version,
            attachments = emptySet(),
            parties = emptySet()
        )
    }

    private fun consentStateToEvent(state: nl.nuts.consent.state.ConsentState): Event {

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