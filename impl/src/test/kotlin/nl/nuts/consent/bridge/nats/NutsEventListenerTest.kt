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
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import net.corda.core.messaging.CordaRPCOps
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.api.ConsentApiService
import nl.nuts.consent.bridge.model.*
import nl.nuts.consent.bridge.rpc.CordaRPClientFactory
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.rpc.CordaService
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.*

class NutsEventListenerTest {

    lateinit var cf : StreamingConnectionFactory
    lateinit var connection: StreamingConnection
    lateinit var nutsEventListener: NutsEventListener

    private var cordaService: CordaService = mock()

    @Before
    fun setup() {
        cf = StreamingConnectionFactory("test-cluster", "cordaBridgeTest-${Integer.toHexString(Random().nextInt())}")

        nutsEventListener = NutsEventListener()
        nutsEventListener.consentBridgeNatsProperties = ConsentBridgeNatsProperties()
        nutsEventListener.cordaService = cordaService

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
    fun `events are ignored when for other modules`() {
        //when
        val e = Serialization.objectMapper().writeValueAsBytes(event(EventName.EventCompleted))
        connection.publish("consentRequest", e)

        // then
    }

    @Test
    fun `requested state is forwarded to consentService`() {
        //when
        val e = Serialization.objectMapper().writeValueAsBytes(newConsentRequestStateAsEvent())
        connection.publish("consentRequest", e)

        Thread.sleep(1000)

        // then
        verify(cordaService).newConsentRequestState(any())
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
                consentId = ConsentId(externalId = "externalId"),
                cipherText = "",
                metadata = nl.nuts.consent.bridge.model.Metadata(
                    domain = listOf(Domain.medical),
                        period = Period(validFrom = OffsetDateTime.now()),
                        organisationSecureKeys = emptyList(),
                        secureKey = SymmetricKey(alg = "alg", iv = "iv")
                ),
                legalEntities = emptyList()
        )
        val emptyJson = Serialization.objectMapper().writeValueAsString(newConsentRequestState)

        return Event(
                UUID = "1111-2222-33334444-5555-6666",
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
                        publicKey = "",
                        data = ""
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