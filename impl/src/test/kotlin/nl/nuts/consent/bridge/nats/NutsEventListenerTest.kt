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
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.api.ConsentApiService
import nl.nuts.consent.bridge.model.*
import nl.nuts.consent.bridge.nats.NutsEventListener.Serialisation.objectMapper
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import java.util.*

class NutsEventListenerTest {

    val cf = StreamingConnectionFactory("test-cluster", "cordaBridgeTest")
    lateinit var connection: StreamingConnection
    lateinit var nutsEventListener: NutsEventListener

    private var consentService: ConsentApiService = mock()

    @Before
    fun setup() {
        nutsEventListener = NutsEventListener()
        nutsEventListener.consentBridgeNatsProperties = ConsentBridgeNatsProperties()
        nutsEventListener.consentService = consentService

        cf.natsUrl = nutsEventListener.consentBridgeNatsProperties.address
        connection = cf.createConnection()
        nutsEventListener.init()
    }

    @After
    fun tearDown() {
        connection.close()
        nutsEventListener.destroy()
    }

    @Test
    fun `non 'requested' or 'accepted' are ignored`() {
        //when
        val e = objectMapper().writeValueAsBytes(event("finalized"))
        connection.publish("consentRequest", e)

        // then
    }

    @Test
    fun `requested state is forwarded to consentService`() {
        //when
        val e = objectMapper().writeValueAsBytes(newConsentRequestStateAsEvent())
        connection.publish("consentRequest", e)

        Thread.sleep(1000)

        // then
        verify(consentService).newConsentRequestState(any())
    }

    @Test
    fun `accepted state is forwarded to consentService`() {
        //when
        val e = objectMapper().writeValueAsBytes(acceptConsentRequestAsEvent())
        connection.publish("consentRequest", e)

        Thread.sleep(1000)

        // then
        verify(consentService).acceptConsentRequestState(eq("consentUuid"), any())
    }

    private fun event(state : String) : Event {
        return Event(
                UUID = "uuid",
                state = state,
                retryCount = 0,
                externalId = "uuid",
                custodian = "custodian",
                payload = "",
                consentId = "consentUuid",
                error = null
        )
    }

    private fun newConsentRequestStateAsEvent() : Event {
        val newConsentRequestState = NewConsentRequestState(
                externalId = "externalId",
                attachment = "",
                metadata = nl.nuts.consent.bridge.model.Metadata(
                    domain = listOf(Domain.medical),
                        period = Period(validFrom = OffsetDateTime.now()),
                        organisationSecureKeys = emptyList(),
                        secureKey = SymmetricKey(alg = "alg", iv = "iv")
                )
        )
        val emptyJson = objectMapper().writeValueAsString(newConsentRequestState)

        return Event(
                UUID = "uuid",
                state = "requested",
                retryCount = 0,
                externalId = "uuid",
                custodian = "custodian",
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
                        publicKey = "",
                        data = ""
                )
        )
        val emptyJson = objectMapper().writeValueAsString(partyAttachmentSignature)

        return Event(
                UUID = "uuid",
                state = "accepted",
                retryCount = 0,
                externalId = "uuid",
                custodian = "custodian",
                payload = Base64.getEncoder().encodeToString(emptyJson.toByteArray()),
                consentId = "consentUuid",
                error = null
        )
    }
}