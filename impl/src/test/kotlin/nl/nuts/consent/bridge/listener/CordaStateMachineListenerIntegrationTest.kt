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
import com.nhaarman.mockito_kotlin.*
import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.contracts.StateAndRef
import net.corda.core.messaging.startFlow
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.PortAllocation
import net.corda.testing.driver.driver
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.nats.*
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.rpc.test.DummyFlow
import nl.nuts.consent.bridge.rpc.test.DummyState
import org.junit.*
import org.mockito.Mockito
import org.mockito.internal.matchers.Equals
import org.mockito.internal.matchers.NotNull
import org.mockito.internal.matchers.NotNull.NOT_NULL
import java.lang.Exception
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull

class CordaStateMachineListenerIntegrationTest {
    companion object {
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
                driver(DriverParameters(extraCordappPackagesToScan = listOf("nl.nuts.consent.bridge.rpc.test")
                        , portAllocation = PortAllocation.Incremental(12000))) {
                    val nodeF = startNode(providedName = ALICE_NAME, rpcUsers = listOf(CordaStateChangeListenerConnectionIntegrationTest.rpcUser))
                    node = nodeF.get()
                    val address = node!!.rpcAddress
                    validProperties = ConsentBridgeRPCProperties(address.host, address.port, CordaStateChangeListenerConnectionIntegrationTest.USER, CordaStateChangeListenerConnectionIntegrationTest.PASSWORD, 1)
                    waitForTests.await()
                    waitForDriver.countDown()
                }
            }.start()

            blockUntilSet(90000L) {
                node
            }
        }

        @AfterClass
        @JvmStatic fun tearDown() {
            waitForTests.countDown()
            waitForDriver.await()
        }
    }

    private var listener : CordaStateMachineListener? = null
    private val eventStateStore = EventStateStore()

    @Before
    fun setup() {
        val client = CordaRPCClient(node!!.rpcAddress, CordaRPCClientConfiguration.DEFAULT.copy(maxReconnectAttempts = 1))
        connection = client.start(CordaStateChangeListenerConnectionIntegrationTest.USER, CordaStateChangeListenerConnectionIntegrationTest.PASSWORD, null, null)
    }

    @After
    fun cleanup() {
        listener?.stop()
        connection?.close()
    }

    @Test
    fun `event is published on state machine error`() {
        val nutsEventPublisher : NutsEventPublisher = mock()
        val uuid:UUID = UUID.randomUUID()
        val eventIn = event(EventName.EventConsentRequestInFlight, uuid)
        val eventOut = event(EventName.EventConsentRequestFlowErrored, uuid)
        eventOut.error = "error"
        listener = CordaStateMachineListener(CordaRPClientWrapper(validProperties!!), nutsEventPublisher, eventStateStore)
        listener!!.start()

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