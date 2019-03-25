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

package nl.nuts.consent.bridge.rpc

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.CordaRPCConnection
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
import nl.nuts.consent.bridge.rpc.test.DummyFlow.ConsumeFlow
import nl.nuts.consent.bridge.rpc.test.DummyFlow.ProduceFlow
import nl.nuts.consent.bridge.rpc.test.DummyState
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * These tests are quite slow....
 * todo: migrate to IT
 */
class StateChangeListenerTest : NodeBasedTest(listOf("nl.nuts.consent.bridge.rpc.test"), notaries = listOf(DUMMY_NOTARY_NAME)) {
    companion object {
        val PASSWORD = "test"
        val USER = "user1"
        val rpcUser = User(USER, PASSWORD, permissions = setOf(all()))
    }

    private lateinit var node: NodeWithInfo
    private lateinit var identity: Party

    private lateinit var client: CordaRPCClient
    private var connection: CordaRPCConnection? = null

    @Before
    override fun setUp() {
        super.setUp()
        node = startNode(ALICE_NAME, rpcUsers = listOf(rpcUser))
        identity = notaryNodes.first().info.identityFromX500Name(DUMMY_NOTARY_NAME)

        // different RPC client for starting flow
        client = CordaRPCClient(node.node.configuration.rpcOptions.address, CordaRPCClientConfiguration.DEFAULT.copy(maxReconnectAttempts = 1))
        connection = client.start(USER, PASSWORD, null, null)

    }

    @After
    fun done() {
        connection?.close()
    }

    @Test
    fun `onProduces is called for a new state`() {
        var count = 0
        val address = node.node.configuration.rpcOptions.address
        val callback = StateChangeListener<DummyState>(ConsentBridgeRPCProperties(address.host, address.port, USER, PASSWORD))
        callback.onProduced { count++ }
        callback.start(DummyState::class.java)

        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        Thread.sleep(2000)

        assertEquals(1, count)

        callback.close()
    }

    @Test
    fun `onProduces is called for each listener`() {
        var count = 0
        val address = node.node.configuration.rpcOptions.address
        repeat(2) {
            val callback = StateChangeListener<DummyState>(ConsentBridgeRPCProperties(address.host, address.port, USER, PASSWORD))
            callback.onProduced { count++ }
            callback.start(DummyState::class.java)
        }

        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        Thread.sleep(2000)

        assertEquals(2, count)
    }

    @Test
    fun `onProduces returns refAndState`() {
        val address = node.node.configuration.rpcOptions.address

        val callback = StateChangeListener<DummyState>(ConsentBridgeRPCProperties(address.host, address.port, USER, PASSWORD))
        var producedState: StateAndRef<DummyState>? = null
        callback.onProduced { producedState = it }
        callback.start(DummyState::class.java)

        // produce 1 state
        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        Thread.sleep(2000)

        assertNotNull(producedState)

        // cleanup
        callback.close()
    }

    @Test
    fun `onConsumes returns refAndState`() {
        val address = node.node.configuration.rpcOptions.address

        val callback = StateChangeListener<DummyState>(ConsentBridgeRPCProperties(address.host, address.port, USER, PASSWORD))
        var producedState: StateAndRef<DummyState>? = null
        var consumedState: StateAndRef<DummyState>? = null
        callback.onProduced { producedState = it }
        callback.onConsumed { consumedState = it }
        callback.start(DummyState::class.java)

        // produce 1 state
        connection!!.proxy.startFlow(::ProduceFlow).returnValue.get()

        // consume 1 state
        connection!!.proxy.startFlow(::ConsumeFlow, producedState!!).returnValue.get()

        Thread.sleep(2000)

        assertNotNull(consumedState)

        // cleanup
        callback.close()
    }
}