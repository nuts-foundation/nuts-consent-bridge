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

import io.nats.client.ConnectionListener
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.messaging.startFlow
import net.corda.node.services.Permissions
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.driver.*
import net.corda.testing.node.User
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.EventMetaProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.corda.CordaManagedConnectionFactory
import nl.nuts.consent.bridge.corda.StateFileStorageControl
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.EventStateStore
import nl.nuts.consent.bridge.nats.NATS_CONSENT_ERROR_SUBJECT
import nl.nuts.consent.bridge.nats.NatsManagedConnectionFactory
import nl.nuts.consent.bridge.rpc.test.DummyFlow
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
import java.io.File
import java.net.ServerSocket
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * These tests are quite slow....
 */
class CordaStateMachineToNatsPipelineIntegrationTest {
    companion object {
        const val USER = "user1"
        const val PASSWORD = "test"
        val rpcUser = User(USER, PASSWORD, permissions = setOf(Permissions.all()))
        var port = 4222
        var natsServer: EmbeddedNatsServer? = null

        fun blockUntilSet(waitTime: Long = 10000L, check: () -> Any?) : Any? {
            val begin = System.currentTimeMillis()
            var x: Any? = null
            while(true) {
                Thread.sleep(10)
                if (System.currentTimeMillis() - begin > waitTime) break
                x = check() ?: continue
                break
            }
            return x
        }

        fun blockUntilNull(waitTime: Long = 10000L, check: () -> Any?) : Any? {
            val begin = System.currentTimeMillis()
            var x: Any? = null
            while(true) {
                Thread.sleep(10)
                if (System.currentTimeMillis() - begin > waitTime) break
                x = check() ?: break
            }
            return x
        }

        var rpcConnection: CordaRPCConnection? = null
        var validProperties : ConsentBridgeRPCProperties? = null
        var node: NodeHandle? = null

        val waitForTests = CountDownLatch(1)
        val waitForDriver = CountDownLatch(1)

        @BeforeClass
        @JvmStatic fun runNodes() {
            Thread {
                // blocking call
                driver(DriverParameters(
                        extraCordappPackagesToScan = listOf("nl.nuts.consent.bridge.rpc.test"),
                        startNodesInProcess = true
                )) {
                    val nodeF = startNode(providedName = ALICE_NAME, rpcUsers = listOf(rpcUser))
                    node = nodeF.get()
                    val address = node!!.rpcAddress
                    validProperties = ConsentBridgeRPCProperties(address.host, address.port, USER, PASSWORD, 1)
                    waitForTests.await()
                    waitForDriver.countDown()
                }
            }.start()

            // nats server
            ServerSocket(0).use { NatsToCordaPipelineIntegrationTest.port = it.localPort }
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

            // wait for corda node
            blockUntilSet(120000L) {
                node
            }
        }

        @AfterClass
        @JvmStatic fun tearDown() {
            natsServer?.stopServer()
            waitForTests.countDown()
            waitForDriver.await()
        }
    }

    private var pipeline : CordaStateMachineToNatsPipeline? = null
    private val eventStateStore = EventStateStore()
    private val stateFileStorage = StateFileStorageControl()
    lateinit var connection: StreamingConnection

    @Before
    fun setup() {
        val client = CordaRPCClient(node!!.rpcAddress, CordaRPCClientConfiguration.DEFAULT.copy(maxReconnectAttempts = 1))
        rpcConnection = client.start(USER, PASSWORD, null, null)
        stateFileStorage.eventMetaProperties = EventMetaProperties("./temp")

        val cf = StreamingConnectionFactory("test-cluster", "cordaBridgeTest-${Integer.toHexString(Random().nextInt())}")
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

    @BeforeTest
    fun reset() {
        // so listener starts starts at 0
        File("./temp/DummyState.stamp").delete()
    }

    @After
    fun cleanup() {
        pipeline?.destroy()
        connection.close()
        rpcConnection?.close()
    }

    private fun createPipeline(): CordaStateMachineToNatsPipeline {
        pipeline = CordaStateMachineToNatsPipeline()

        // nats connections
        val natsManagedConnectionFactory = NatsManagedConnectionFactory()
        natsManagedConnectionFactory.consentBridgeNatsProperties = ConsentBridgeNatsProperties(address = "nats://localhost:$port", retryIntervalSeconds = 1)
        pipeline?.natsManagedConnectionFactory = natsManagedConnectionFactory

        //corda connections
        val cordaManagedConnectionFactory = CordaManagedConnectionFactory()
        cordaManagedConnectionFactory.consentBridgeRPCProperties = validProperties!!
        pipeline?.cordaManagedConnectionFactory = cordaManagedConnectionFactory

        // stateFileStorage
        pipeline?.eventStateStore = eventStateStore

        return pipeline!!
    }

    @Test
    fun `event is published on state machine error`() {
        val uuid: UUID = UUID.randomUUID()
        val eventIn = event(EventName.EventConsentRequestInFlight, uuid)
        var eventOut: Event? = null
        pipeline = createPipeline()
        pipeline?.init()

        connection.subscribe(NATS_CONSENT_ERROR_SUBJECT) {
            eventOut = Serialization.objectMapper().readValue(it.data, Event::class.java)
        }

        val handle = rpcConnection!!.proxy.startFlow(DummyFlow::ErrorFlow)
        eventStateStore.put(handle.id.uuid, eventIn)

        // wait for it
        blockUntilNull {
            eventStateStore.get(handle.id.uuid)
        }

        // verify updated event
        assertEquals(EventName.EventConsentRequestInFlight, eventOut?.name)
        assertNotNull(eventOut?.error)
        assertEquals("", eventOut?.error)
    }

    private fun event(name: EventName, uuid: UUID) : Event {
        return Event(
                UUID = uuid.toString(),
                name = name,
                retryCount = 0,
                payload = "",
                externalId = "externalId"
        )
    }
}