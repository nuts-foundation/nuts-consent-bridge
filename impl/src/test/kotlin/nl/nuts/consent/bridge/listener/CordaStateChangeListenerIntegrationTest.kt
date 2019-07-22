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

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.Party
import net.corda.core.messaging.startFlow
import net.corda.node.internal.NodeWithInfo
import net.corda.node.services.Permissions.Companion.all
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.node.User
import net.corda.testing.node.internal.NodeBasedTest
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.rpc.test.DummyFlow.ConsumeFlow
import nl.nuts.consent.bridge.rpc.test.DummyFlow.ProduceFlow
import nl.nuts.consent.bridge.rpc.test.DummyState
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * These tests are quite slow....
 */
class CordaStateChangeListenerIntegrationTest : NodeBasedTest(listOf("nl.nuts.consent.bridge.rpc.test"), notaries = listOf(DUMMY_NOTARY_NAME)) {
    companion object {
        val PASSWORD = "test"
        val USER = "user1"
        val rpcUser = User(USER, PASSWORD, permissions = setOf(all()))

        fun blockUntilSet(check: () -> Any?) : Any? {
            val begin = System.currentTimeMillis()
            var x: Any? = null
            while(true) {
                Thread.sleep(10)
                if (System.currentTimeMillis() - begin > 10000) break
                x = check() ?: continue
                break
            }
            return x
        }
    }

    private lateinit var node: NodeWithInfo
    private lateinit var identity: Party

    private lateinit var client: CordaRPCClient
    private var connection: CordaRPCConnection? = null

    private lateinit var validProperties : ConsentBridgeRPCProperties

    @Before
    override fun setUp() {
        super.setUp()
        node = startNode(ALICE_NAME, rpcUsers = listOf(rpcUser))
        identity = notaryNodes.first().info.identityFromX500Name(DUMMY_NOTARY_NAME)

        // different RPC client for starting flow
        client = CordaRPCClient(node.node.configuration.rpcOptions.address, CordaRPCClientConfiguration.DEFAULT.copy(maxReconnectAttempts = 1))
        connection = client.start(USER, PASSWORD, null, null)

        val address = node.node.configuration.rpcOptions.address
        validProperties = ConsentBridgeRPCProperties(address.host, address.port, USER, PASSWORD)
    }

    @After
    fun done() {
        connection?.close()
    }

    @Test
    fun `onProduces is not called for a new state when callback was stopped`() {
        var count = AtomicInteger(0)
        val listener = CordaStateChangeListener<DummyState>(CordaRPClientWrapper(validProperties), 0, {
            count.incrementAndGet()
        })
        listener.stop()
        listener.start(DummyState::class.java)

        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        assertEquals(0, count.get())
    }

    @Test
    fun `onProduces is called for a new state`() {
        var count = AtomicInteger(0)
        val listener = CordaStateChangeListener<DummyState>(CordaRPClientWrapper(validProperties), 0, {
            count.incrementAndGet()
        })
        listener.start(DummyState::class.java)

        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        assertEquals(1, count.get())

        listener.stop()
    }

    @Test
    fun `onProduces returns refAndState`() {
        var producedState = AtomicReference<StateAndRef<DummyState>>()
        val listener = CordaStateChangeListener<DummyState>(CordaRPClientWrapper(validProperties), 0, {
            producedState.set(it)
        })
        listener.start(DummyState::class.java)

        // produce 1 state
        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        blockUntilSet {
            producedState.get()
        }
        assertNotNull(producedState.get())

        // cleanup
        listener.stop()
    }

    @Test
    fun `onConsumes returns refAndState`() {
        var producedState = AtomicReference<StateAndRef<DummyState>>()
        var consumedState = AtomicReference<StateAndRef<DummyState>>()
        val listener = CordaStateChangeListener<DummyState>(CordaRPClientWrapper(validProperties), 0, {
            producedState.set(it)
        },{
            consumedState.set(it)
        })
        listener.start(DummyState::class.java)

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

        // cleanup
        listener.stop()
    }
}