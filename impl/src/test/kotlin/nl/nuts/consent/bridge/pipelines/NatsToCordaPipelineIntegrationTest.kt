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
import io.nats.client.ConnectionListener
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import net.corda.core.CordaRuntimeException
import net.corda.core.flows.StateMachineRunId
import net.corda.core.messaging.FlowHandle
import net.corda.core.transactions.SignedTransaction
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
import nl.nuts.consent.bridge.nats.NatsManagedConnection
import nl.nuts.consent.bridge.nats.NatsManagedConnectionFactory
import np.com.madanpokharel.embed.nats.EmbeddedNatsConfig
import np.com.madanpokharel.embed.nats.EmbeddedNatsServer
import np.com.madanpokharel.embed.nats.NatsServerConfig
import np.com.madanpokharel.embed.nats.NatsStreamingVersion
import np.com.madanpokharel.embed.nats.ServerType
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito.`when`
import java.net.ServerSocket
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


class NatsToCordaPipelineIntegrationTest {

    lateinit var cf : StreamingConnectionFactory
    lateinit var connection: StreamingConnection
    lateinit var natsToCordaPipeline: NatsToCordaPipeline
    lateinit var natsManagedConnection: NatsManagedConnection
    lateinit var cordaService: CordaService
    lateinit var natsManagedConnectionFactory: NatsManagedConnectionFactory
    lateinit var cordaManagedConnectionFactory: CordaManagedConnectionFactory

    companion object {
        var natsServer: EmbeddedNatsServer? = null
        var port = 4222

        @BeforeClass @JvmStatic fun setupClass() {
            // server
            ServerSocket(0).use { port = it.localPort }
            val config = EmbeddedNatsConfig.Builder()
                .withNatsServerConfig(
                    NatsServerConfig.Builder()
                        .withServerType(ServerType.NATS_STREAMING)
                        .withPort(port)
                        .withNatsStreamingVersion(NatsStreamingVersion.V0_16_2)
                        .build()
                )
                .build()
            natsServer = EmbeddedNatsServer(config)
            natsServer?.startServer()
        }

        @AfterClass @JvmStatic fun tearDownClass() {
            natsServer?.stopServer()
        }
    }

    @Before
    fun setup() {
        cf = StreamingConnectionFactory("test-cluster", "cordaBridgeTest-${Integer.toHexString(Random().nextInt())}")

        cordaService = mock()
        natsManagedConnectionFactory = mock()
        cordaManagedConnectionFactory = mock()
        natsManagedConnection = NatsManagedConnection(ConsentBridgeNatsProperties("nats://localhost:$port", "test-cluster", 1, 1))
        `when`(natsManagedConnectionFactory.`object`).thenReturn(natsManagedConnection)
        `when`(cordaManagedConnectionFactory.`object`).thenReturn(mock())
        natsToCordaPipeline = initPipeline()

        cf.natsUrl = natsServer?.natsUrl

        val l = CountDownLatch(1)

        // client connection listener
        val listener = ConnectionListener { conn, type ->
            when(type) {
                ConnectionListener.Events.RECONNECTED,
                ConnectionListener.Events.CONNECTED -> {
                    // notify
                    cf.natsConnection = conn
                    connection = cf.createConnection()
                    l.countDown()
                }
            }
        }

        // client
        val o = Options.Builder()
                .server(natsServer?.natsUrl)
                .maxReconnects(-1)
                .connectionListener(listener)
                .build()
        Nats.connectAsynchronously(o, false)

        l.await(10, TimeUnit.SECONDS)

    }

    @After
    fun tearDown() {
        connection?.close()
        natsToCordaPipeline.destroy()
    }

    private fun initPipeline() : NatsToCordaPipeline {
        val natsToCordaPipeline = NatsToCordaPipeline()
        natsToCordaPipeline.eventStateStore = EventStateStore()
        natsToCordaPipeline.cordaManagedConnectionFactory = cordaManagedConnectionFactory
        natsToCordaPipeline.natsManagedConnectionFactory = natsManagedConnectionFactory
        natsToCordaPipeline.consentRegistryProperties = ConsentRegistryProperties()

        natsToCordaPipeline.init()

        // overwrite with mock
        natsToCordaPipeline.cordaService = cordaService

        natsToCordaPipeline.natsManagedConnection.connect()

        return natsToCordaPipeline
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

        // disconnect nats listeners
        natsManagedConnection.disconnect()

        // publish
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, e)

        // simulate connection being back
        natsManagedConnection.onConnected()

        Thread.sleep(1000)

        // then
        verify(cordaService).createConsentBranch(any())
    }

    @Test
    fun `event is published to error queue on json error`() {
        // given
        val streamingConnection: StreamingConnection = mock()
        val nmc: NatsManagedConnection = mock()
        `when`(nmc.getConnection()).thenReturn(streamingConnection)
        natsToCordaPipeline.natsManagedConnection = nmc

        // when
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, "not json".toByteArray())

        Thread.sleep(1000)

        // then an entry must be available in the retry queue
        verify(streamingConnection).publish(eq(NATS_CONSENT_ERROR_SUBJECT), any())
    }

    @Test
    fun `event is published to error queue on unknown json error`() {
        // given
        val streamingConnection: StreamingConnection = mock()
        val nmc: NatsManagedConnection = mock()
        `when`(nmc.getConnection()).thenReturn(streamingConnection)
        natsToCordaPipeline.natsManagedConnection = nmc

        // when
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, "[]".toByteArray())

        Thread.sleep(1000)

        // then an entry must be available in the retry queue
        verify(streamingConnection).publish(eq(NATS_CONSENT_ERROR_SUBJECT), any())
    }

    @Test
    fun `event is published to retry queue on CordaError`() {
        val t: FlowHandle<SignedTransaction> = mock()
        val st: StateMachineRunId = mock()
        val streamingConnection: StreamingConnection = mock()
        val nmc: NatsManagedConnection = mock()
        `when`(t.id).thenReturn(st)
        `when`(st.uuid).thenReturn(UUID.randomUUID())
        `when`(nmc.getConnection()).thenReturn(streamingConnection)
        natsToCordaPipeline.natsManagedConnection = nmc

        //when
        `when`(cordaService.consentBranchExists(any())).thenThrow(CordaRuntimeException("boom!"))
        var event = Serialization.objectMapper().writeValueAsString(newConsentRequestStateAsEvent())
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, event.toByteArray())

        Thread.sleep(1000)

        // then an entry must be available in the retry queue
        verify(streamingConnection).publish(eq("consentRequestRetry-1"), any())
    }

    @Test
    fun `event is published to error queue on unknown exception`() {
        val t: FlowHandle<SignedTransaction> = mock()
        val st: StateMachineRunId = mock()
        val streamingConnection: StreamingConnection = mock()
        val nmc: NatsManagedConnection = mock()
        `when`(t.id).thenReturn(st)
        `when`(st.uuid).thenReturn(UUID.randomUUID())
        `when`(nmc.getConnection()).thenReturn(streamingConnection)
        natsToCordaPipeline.natsManagedConnection = nmc

        //when
        `when`(cordaService.consentBranchExists(any())).thenThrow(IllegalStateException("boom!"))
        var event = Serialization.objectMapper().writeValueAsString(newConsentRequestStateAsEvent())
        connection.publish(NATS_CONSENT_REQUEST_SUBJECT, event.toByteArray())

        Thread.sleep(1000)

        // then an entry must be available in the retry queue
        verify(streamingConnection).publish(eq(NATS_CONSENT_ERROR_SUBJECT), any())
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