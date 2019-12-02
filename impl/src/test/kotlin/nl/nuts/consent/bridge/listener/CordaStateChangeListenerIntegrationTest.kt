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

package nl.nuts.consent.bridge.listener

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.contains
import com.natpryce.hamkrest.equalTo
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.contracts.StateAndRef
import net.corda.core.internal.concurrent.map
import net.corda.core.messaging.startFlow
import net.corda.node.services.Permissions
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.driver.*
import net.corda.testing.node.User
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.EventStateStore
import nl.nuts.consent.bridge.nats.NutsEventPublisher
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.rpc.test.DummyFlow
import nl.nuts.consent.bridge.rpc.test.DummyFlow.ConsumeFlow
import nl.nuts.consent.bridge.rpc.test.DummyFlow.ProduceFlow
import nl.nuts.consent.bridge.rpc.test.DummyState
import org.junit.*
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * These tests are quite slow....
 */
class CordaStateChangeListenerIntegrationTest {
    companion object {
        const val USER = "user1"
        const val PASSWORD = "test"
        val rpcUser = User(USER, PASSWORD, permissions = setOf(Permissions.all()))

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

        var connection: CordaRPCConnection? = null
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
                        startNodesInProcess = false
                )) {
                    val nodeF = startNode(providedName = ALICE_NAME, rpcUsers = listOf(rpcUser))
                    node = nodeF.get()
                    val address = node!!.rpcAddress
                    validProperties = ConsentBridgeRPCProperties(address.host, address.port, USER, PASSWORD, 1)
                    waitForTests.await()
                    waitForDriver.countDown()
                }
            }.start()

            blockUntilSet(120000L) {
                node
            }
        }

        @AfterClass
        @JvmStatic fun tearDown() {
            waitForTests.countDown()
            waitForDriver.await()
        }
    }

    private var listener : CordaStateChangeListener<DummyState>? = null
    private var smListener : CordaStateMachineListener? = null
    private val eventStateStore = EventStateStore()

    @Before
    fun setup() {
        val client = CordaRPCClient(node!!.rpcAddress, CordaRPCClientConfiguration.DEFAULT.copy(maxReconnectAttempts = 1))
        connection = client.start(USER, PASSWORD, null, null)
    }

    @After
    fun cleanup() {
        listener?.stop()
        connection?.close()
    }

    @Test
    fun `onProduces is called for a new state`() {
        val producedState = AtomicReference<StateAndRef<DummyState>>()
        listener = CordaStateChangeListener<DummyState>(CordaRPClientWrapper(validProperties!!), {
            producedState.set(it)
        })
        listener!!.start(DummyState::class.java)

        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        blockUntilSet {
            producedState.get()
        }

        assertNotNull(producedState.get())
    }

    @Test
    fun `onProduces returns refAndState`() {
        val producedState = AtomicReference<StateAndRef<DummyState>>()
        listener = CordaStateChangeListener<DummyState>(CordaRPClientWrapper(validProperties!!), {
            producedState.set(it)
        })
        listener!!.start(DummyState::class.java)

        // produce 1 state
        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        blockUntilSet {
            producedState.get()
        }
        assertNotNull(producedState.get())
    }

    @Test
    fun `onConsumes returns refAndState`() {
        val producedState = AtomicReference<StateAndRef<DummyState>>()
        val consumedState = AtomicReference<StateAndRef<DummyState>>()
        listener = CordaStateChangeListener<DummyState>(CordaRPClientWrapper(validProperties!!), {
            producedState.set(it)
        }, {
            consumedState.set(it)
        })
        listener!!.start(DummyState::class.java)

        // produce 1 state
        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        blockUntilSet {
            producedState.get()
        }
        assertNotNull(producedState.get())

        // consume 1 state
        val signedTransaction = connection!!.proxy.startFlow(::ConsumeFlow, producedState.get()).returnValue.get()
        assertNotNull(signedTransaction)

        blockUntilSet {
            consumedState.get()
        }
        assertNotNull(consumedState.get())
    }

    @Test
    fun `event is published on state machine error`() {
        val nutsEventPublisher : NutsEventPublisher = mock()
        val uuid: UUID = UUID.randomUUID()
        val eventIn = event(EventName.EventConsentRequestInFlight, uuid)
        val eventOut = event(EventName.EventConsentRequestFlowErrored, uuid)
        eventOut.error = "error"
        smListener = CordaStateMachineListener(CordaRPClientWrapper(validProperties!!), nutsEventPublisher, eventStateStore)
        smListener!!.start()

        val handle = connection!!.proxy.startFlow(DummyFlow::ErrorFlow)
        eventStateStore.put(handle.id.uuid, eventIn)

        // wait for it
        blockUntilNull {
            eventStateStore.get(handle.id.uuid)
        }

        // verify updated event
        verify(nutsEventPublisher).publish(eq("consentRequestErrored"), com.nhaarman.mockito_kotlin.check {
            assertThat(Serialization.objectMapper().readValue(it, Event::class.java).error!!, contains(Regex.fromLiteral("error")))
            assertThat(Serialization.objectMapper().readValue(it, Event::class.java).name, equalTo(EventName.EventConsentRequestInFlight))
        })
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