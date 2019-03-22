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
import net.corda.core.identity.Party
import net.corda.core.messaging.startFlow
import net.corda.node.internal.NodeWithInfo
import net.corda.node.services.Permissions
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.node.User
import net.corda.testing.node.internal.NodeBasedTest
import nl.nuts.consent.bridge.CordaRPCProperties
import nl.nuts.consent.bridge.rpc.test.DummyFlow
import nl.nuts.consent.bridge.rpc.test.DummyState
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class StateChangeListenerConnectionTest  : NodeBasedTest(listOf("nl.nuts.consent.bridge.rpc.test"), notaries = listOf(DUMMY_NOTARY_NAME)) {
    companion object {
        val PASSWORD = "test"
        val USER = "user1"
        val rpcUser = User(USER, PASSWORD, permissions = setOf(Permissions.all()))
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
    fun `callbacks survive node stop and start`() {
        var count = 0
        val address = node.node.configuration.rpcOptions.address
        val callback = StateChangeListener<DummyState>(CordaRPCProperties(address.host, address.port, USER, PASSWORD, 1))
        callback.onProduced { count++ }
        callback.start(DummyState::class.java)

        // restart node
        node.dispose()
        node = startNode(ALICE_NAME, rpcUsers = listOf(rpcUser), configOverrides = mapOf("rpcSettings.address" to address.toString()))

        connection = client.start(USER, PASSWORD, null, null)
        val nodeInfo = connection!!.proxy.nodeInfo()
        require(nodeInfo.legalIdentitiesAndCerts.isNotEmpty())

        // todo: this means that the reconnect should have an offset of failureTime - ~60 seconds for the update feed
        Thread.sleep(10000)

        // start flow after restart of node
        connection!!.proxy.startFlow(DummyFlow::ProduceFlow).returnValue.get()

        // should still have been captured
        assertEquals(1, count)

        // cleanup
        callback.close()
        connection!!.close()
    }
}