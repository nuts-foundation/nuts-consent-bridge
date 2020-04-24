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
import com.nhaarman.mockito_kotlin.mock
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import io.nats.streaming.SubscriptionOptions
import net.corda.core.CordaRuntimeException
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.ConsentRegistryProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.corda.CordaManagedConnectionFactory
import nl.nuts.consent.bridge.corda.CordaService
import nl.nuts.consent.bridge.model.ConsentId
import nl.nuts.consent.bridge.model.ConsentRecord
import nl.nuts.consent.bridge.model.Domain
import nl.nuts.consent.bridge.model.FullConsentRequestState
import nl.nuts.consent.bridge.model.Period
import nl.nuts.consent.bridge.model.SymmetricKey
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.EventStateStore
import nl.nuts.consent.bridge.nats.NATS_CONSENT_ERROR_SUBJECT
import nl.nuts.consent.bridge.nats.NATS_CONSENT_REQUEST_SUBJECT
import nl.nuts.consent.bridge.nats.NATS_CONSENT_RETRY_SUBJECT
import nl.nuts.consent.bridge.nats.NatsManagedConnection
import nl.nuts.consent.bridge.nats.NatsManagedConnectionFactory
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.*
import kotlin.collections.ArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class NatsToCordaPipelineIntegrationTest {

    companion object {
        var port = 4222
        var erroredEvents = ArrayList<String>()
        var retryEvents = ArrayList<String>()
        lateinit var connection: StreamingConnection
        lateinit var natsToCordaPipeline: NatsToCordaPipeline
        lateinit var natsManagedConnection: NatsManagedConnection
        lateinit var cordaService: CordaService
        lateinit var natsConnectionFactory: NatsManagedConnectionFactory
        lateinit var cordaManagedConnectionFactory: CordaManagedConnectionFactory

        @BeforeClass @JvmStatic fun setupClass() {
            val o = Options.Builder().server("localhost:$port").build()
            val cf = StreamingConnectionFactory("test-cluster", "cordaBridgeTestErrors-${Integer.toHexString(Random().nextInt())}")
            cf.natsConnection = Nats.connect(o)
            connection = cf.createConnection()
            connection.subscribe(NATS_CONSENT_ERROR_SUBJECT, {
                erroredEvents.add(String(it.data))
            }, SubscriptionOptions.Builder()
                .deliverAllAvailable()
                .build()
            )
            connection.subscribe("${NATS_CONSENT_RETRY_SUBJECT}-1", {
                retryEvents.add(String(it.data))
            }, SubscriptionOptions.Builder()
                .deliverAllAvailable()
                .build()
            )

            initPipeline()
        }

        @AfterClass @JvmStatic fun tearDownClass() {
            connection?.close()
        }

        fun initPipeline() {
            cordaService = mock()
            natsConnectionFactory = mock()
            cordaManagedConnectionFactory = mock()
            natsManagedConnection = NatsManagedConnection(ConsentBridgeNatsProperties("nats://localhost:$port", "test-cluster", 1, 1))
            `when`(natsConnectionFactory.`object`).thenReturn(natsManagedConnection)
            `when`(cordaManagedConnectionFactory.`object`).thenReturn(mock())

            natsToCordaPipeline = NatsToCordaPipeline()
            natsToCordaPipeline.eventStateStore = EventStateStore()
            natsToCordaPipeline.cordaManagedConnectionFactory = cordaManagedConnectionFactory
            natsToCordaPipeline.natsManagedConnectionFactory = natsConnectionFactory
            natsToCordaPipeline.consentRegistryProperties = ConsentRegistryProperties()
            natsToCordaPipeline.stateFileStorageControl = mock()

            natsToCordaPipeline.init()

            // overwrite with mock
            natsToCordaPipeline.cordaService = cordaService
        }
    }

    @Before
    fun setup() {
        erroredEvents.clear()
        retryEvents.clear()

        natsToCordaPipeline.natsManagedConnection.connect()
    }

    @After
    fun tearDown() {
        natsToCordaPipeline.natsManagedConnection.disconnect()
    }

    @Test
    fun `events published when nats connection was down, are received`() {
        val nmc = natsToCordaPipeline.natsManagedConnection

        // wait for initial callback to have passed
        Thread.sleep(100)

        // stop listener
        nmc.disconnect()

        // publish
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, "not json".toByteArray())

        // reconnect
        nmc.connect()

        Thread.sleep(2000)
        // assert
        assertEquals(1, erroredEvents.size)
    }

    @Test
    fun `event is published to error queue on json error`() {
        // when
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, "not json".toByteArray())

        Thread.sleep(2000)
        // assert
        assertEquals(1, erroredEvents.size)
    }

    @Test
    fun `event is published to error queue on unknown json error`() {
        // when
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, "[]".toByteArray())

        Thread.sleep(2000)
        // assert
        assertEquals(1, erroredEvents.size)

        val e: Event = Serialization.objectMapper().readValue(erroredEvents.first().byteInputStream(), Event::class.java)
        assertEquals(EventName.EventErrored, e.name)
        assertTrue(e.error!!.contains("Cannot deserialize"))
    }

    @Test
    fun `event is published to retry queue on CordaError`() {
        //when
        `when`(cordaService.consentBranchExists(any())).thenThrow(CordaRuntimeException("boom!"))
        val event = Serialization.objectMapper().writeValueAsString(newConsentRequestStateAsEvent())
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, event.toByteArray())

        Thread.sleep(2000)

        // then an entry must be available in the retry queue
        assertEquals(1, retryEvents.size)
    }

    @Test
    fun `event is published to error queue on unknown exception`() {
        //when
        `when`(cordaService.consentBranchExists(any())).thenThrow(IllegalArgumentException("boom!"))
        val event = Serialization.objectMapper().writeValueAsString(newConsentRequestStateAsEvent())
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, event.toByteArray())

        Thread.sleep(2000)

        // then an entry must be available in the retry queue
        assertEquals(1, erroredEvents.size)

        val e: Event = Serialization.objectMapper().readValue(erroredEvents.first().byteInputStream(), Event::class.java)
        assertEquals(EventName.EventErrored, e.name)
        assertEquals("boom!", e.error)
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
}