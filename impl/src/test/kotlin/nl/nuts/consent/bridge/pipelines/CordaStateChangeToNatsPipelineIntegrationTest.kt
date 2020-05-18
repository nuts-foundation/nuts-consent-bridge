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

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.contracts.StateAndRef
import net.corda.core.messaging.startFlow
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.ConsentRegistryProperties
import nl.nuts.consent.bridge.EventMetaProperties
import nl.nuts.consent.bridge.EventStoreProperties
import nl.nuts.consent.bridge.corda.CordaManagedConnectionFactory
import nl.nuts.consent.bridge.StateFileStorageControl
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.NatsManagedConnectionFactory
import nl.nuts.consent.bridge.corda.test.DummyFlow.ConsumeFlow
import nl.nuts.consent.bridge.corda.test.DummyFlow.ProduceFlow
import nl.nuts.consent.bridge.corda.test.DummyState
import org.junit.*
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.assertNotNull

/**
 * These tests are quite slow....
 */
class CordaStateChangeToNatsPipelineIntegrationTest : NodeBasedIntegrationTest() {
    private var connection: CordaRPCConnection? = null
    private var pipeline : CordaStateChangeToNatsPipeline<DummyState>? = null
    private val stateFileStorage = StateFileStorageControl()

    @Before
    fun setup() {
        val client = CordaRPCClient(node!!.rpcAddress, CordaRPCClientConfiguration.DEFAULT.copy(maxReconnectAttempts = 1))
        connection = client.start(USER, PASSWORD, null, null)
        stateFileStorage.eventMetaProperties = EventMetaProperties("./temp")
    }

    @BeforeTest
    fun reset() {
        // so listener starts starts at 0
        File("./temp/DummyState.stamp").delete()
    }

    @After
    fun cleanup() {
        pipeline?.destroy()
        connection?.close()
    }

    private fun createPipeline(producedState: AtomicReference<StateAndRef<DummyState>>, consumedState: AtomicReference<StateAndRef<DummyState>>): CordaStateChangeToNatsPipeline<DummyState> {
        pipeline = object : CordaStateChangeToNatsPipeline<DummyState>() {
            override fun name(): String {
                return "test"
            }

            override fun stateClass(): Class<DummyState> {
                return DummyState::class.java
            }

            override fun stateProduced(stateAndRef: StateAndRef<DummyState>) {
                producedState.set(stateAndRef)
            }

            override fun stateConsumed(stateAndRef: StateAndRef<DummyState>) {
                consumedState.set(stateAndRef)
            }
        }

        // nats connections
        val natsManagedConnectionFactory = NatsManagedConnectionFactory()
        natsManagedConnectionFactory.consentBridgeNatsProperties = ConsentBridgeNatsProperties(address = "nats://localhost:${port}", retryIntervalSeconds = 1)
        pipeline?.natsManagedConnectionFactory = natsManagedConnectionFactory

        //corda connections
        val cordaManagedConnectionFactory = CordaManagedConnectionFactory()
        cordaManagedConnectionFactory.consentBridgeRPCProperties = validProperties!!
        pipeline?.cordaManagedConnectionFactory = cordaManagedConnectionFactory

        // registry api
        pipeline?.consentRegistryProperties = ConsentRegistryProperties()

        // stateFileStorage
        pipeline?.stateFileStorageControl = stateFileStorage

        return pipeline!!
    }

    @Test
    fun `listener is able to publish older states`() {
        val producedState = AtomicReference<StateAndRef<DummyState>>()
        pipeline = createPipeline(producedState, AtomicReference<StateAndRef<DummyState>>())
        pipeline?.init()

        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        blockUntilSet {
            producedState.get()
        }

        pipeline!!.cordaManagedConnection.onDisconnected()

        // clean timestamp file
        File("./temp/DummyState.stamp").delete()
        producedState.set(null)

        pipeline!!.cordaManagedConnection.onConnected()

        blockUntilSet {
            producedState.get()
        }

        assertNotNull(producedState.get())
    }

    @Test
    fun `onProduces is called for a new state`() {
        val producedState = AtomicReference<StateAndRef<DummyState>>()
        pipeline = createPipeline(producedState, AtomicReference<StateAndRef<DummyState>>())
        pipeline?.init()

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
        pipeline = createPipeline(producedState, consumedState)
        pipeline?.init()

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
}