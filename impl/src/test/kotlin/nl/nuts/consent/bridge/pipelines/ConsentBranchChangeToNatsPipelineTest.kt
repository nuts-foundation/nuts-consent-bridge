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
import io.nats.streaming.StreamingConnection
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.UniqueIdentifier
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.corda.CordaService
import nl.nuts.consent.bridge.events.apis.EventApi
import nl.nuts.consent.bridge.events.infrastructure.ClientException
import nl.nuts.consent.bridge.model.ConsentId
import nl.nuts.consent.bridge.model.ConsentRecord
import nl.nuts.consent.bridge.model.Domain
import nl.nuts.consent.bridge.model.FullConsentRequestState
import nl.nuts.consent.bridge.model.Period
import nl.nuts.consent.bridge.model.SymmetricKey
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.NATS_CONSENT_REQUEST_SUBJECT
import nl.nuts.consent.bridge.nats.NatsManagedConnection
import nl.nuts.consent.state.ConsentBranch
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.*
import nl.nuts.consent.bridge.model.Metadata as Metadata1

class ConsentBranchChangeToNatsPipelineTest {

    lateinit var consentBranchChangeToCordaPipeline: ConsentBranchChangeToNatsPipeline

    val cordaService: CordaService = mock()
    val eventApi: EventApi = mock()
    val streamingConnection: StreamingConnection = mock()
    val natsManagedConnection: NatsManagedConnection = mock()

    @Before
    fun setup() {
        `when`(natsManagedConnection.getConnection()).thenReturn(streamingConnection)

        consentBranchChangeToCordaPipeline = ConsentBranchChangeToNatsPipeline()

        consentBranchChangeToCordaPipeline.cordaService = cordaService
        consentBranchChangeToCordaPipeline.natsManagedConnection = natsManagedConnection
        consentBranchChangeToCordaPipeline.eventApi = eventApi
    }

    @Test
    fun `Observed produced ConsentBranch publishes new event`() {
        // given
        val s = consentRequestState()
        val e = consentRequestStateToEvent(s)
        val state: TransactionState<ConsentBranch> = mock()
        `when`(state.data).thenReturn(s)
        `when`(cordaService.consentBranchToEvent(any())).thenReturn(e)
        `when`(eventApi.getEvent(s.linearId.id)).thenThrow(ClientException())

        // when
        consentBranchChangeToCordaPipeline.stateProduced(StateAndRef(state, ref = mock()))

        // then publish is called
        verifyPublishedEvent(e)
    }

    @Test
    fun `Observed produced ConsentBranch reuses existing event when found`() {
        // given
        val s = consentRequestState()
        val eCheck = consentRequestStateToEvent(s)
        val eMock = consentRequestStateToEvent(s)
        eMock.payload = ""
        val state: TransactionState<ConsentBranch> = mock()
        val n = storeEvent(nl.nuts.consent.bridge.events.models.Event.Name.distributedConsentRequestReceived, s.linearId.id.toString())
        `when`(state.data).thenReturn(s)
        `when`(cordaService.consentBranchToEvent(any())).thenReturn(eMock)
        `when`(eventApi.getEvent(s.linearId.id)).thenReturn(n)

        // when
        consentBranchChangeToCordaPipeline.stateProduced(StateAndRef(state, ref = mock()))

        // fields to copy
        eCheck.UUID = n.uuid
        eCheck.initiatorLegalEntity = n.initiatorLegalEntity
        eCheck.retryCount = n.retryCount

        // ignore for this test
        eCheck.payload = ""

        // then publish is called
        verifyPublishedEvent(eCheck)
    }

    private fun verifyPublishedEvent(event: Event) {
        verify(streamingConnection).publish(eq(NATS_CONSENT_REQUEST_SUBJECT), eq(Serialization.objectMapper().writeValueAsBytes(event)), any())
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

    private fun consentRequestState(): ConsentBranch {
        return ConsentBranch(
            uuid = UniqueIdentifier(externalId = "externalId"),
            branchPoint = UniqueIdentifier(),
            attachments = emptySet(),
            legalEntities = emptySet(),
            signatures = emptyList(),
            parties = emptySet()
        )
    }

    private fun consentRequestStateToEvent(state: ConsentBranch): Event {

        val ncrs = FullConsentRequestState(
            consentId = ConsentId(UUID = state.linearId.id.toString(), externalId = state.linearId.externalId!!),
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
            )),
            initiatingLegalEntity = ""
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
}