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
import net.corda.core.contracts.StateAndRef
import net.corda.core.messaging.startFlow
import net.corda.node.services.Permissions
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.rpc.test.DummyFlow
import nl.nuts.consent.bridge.rpc.test.DummyState
import org.apache.activemq.artemis.api.core.ActiveMQSecurityException
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CordaStateChangeListenerConnectionIntegrationTest {
    companion object {
        val PASSWORD = "test"
        val USER = "user1"
        val rpcUser = User(USER, PASSWORD, permissions = setOf(Permissions.all()))
    }

    var node: NodeHandle? = null

    private lateinit var client: CordaRPCClient
    private var connection: CordaRPCConnection? = null

    @After
    fun done() {
        connection?.close()
    }

    fun runWithNode(func: () -> Unit) {
        driver(DriverParameters(extraCordappPackagesToScan = listOf("nl.nuts.consent.bridge.rpc.test"), startNodesInProcess = true)) {
            val nodeF = startNode(providedName = ALICE_NAME, rpcUsers = listOf(rpcUser))
            node = nodeF.get()
            client = CordaRPCClient(node!!.rpcAddress, CordaRPCClientConfiguration.DEFAULT.copy(maxReconnectAttempts = 1))
            connection = client.start(USER, PASSWORD, null, null)
            func()
        }
    }

    @Test
    fun `callbacks survive node stop and start`() {
        var producedState = AtomicReference<StateAndRef<DummyState>>()
        var listener: CordaStateChangeListener<DummyState>? = null
        runWithNode {
            val address = node!!.rpcAddress
            listener = CordaStateChangeListener<DummyState>(CordaRPClientWrapper(ConsentBridgeRPCProperties(address.host, address.port, USER, PASSWORD, 1)), {
                producedState.set(it)
            })
            listener!!.start(DummyState::class.java)
        }

        runWithNode {
            connection = client.start(USER, PASSWORD, null, null)
            val nodeInfo = connection!!.proxy.nodeInfo()
            require(nodeInfo.legalIdentitiesAndCerts.isNotEmpty())

            // todo: this means that the reconnect should have an offset of failureTime - ~60 seconds for the update feed
            Thread.sleep(10000)

            // start flow after restart of node
            connection!!.proxy.startFlow(DummyFlow::ProduceFlow).returnValue.get()

            CordaStateChangeListenerIntegrationTest.blockUntilSet {
                producedState.get()
            }

            // should still have been captured
            assertNotNull(producedState.get())

            // cleanup
            listener!!.stop()
            connection!!.close()
        }
    }

    @Test
    fun `Incorrect credentials raises`() {
        runWithNode {
            val address = node!!.rpcAddress
            val callback = CordaStateChangeListener<DummyState>(CordaRPClientWrapper(ConsentBridgeRPCProperties(address.host, address.port, "not user", PASSWORD, 1)))
            assertFailsWith<ActiveMQSecurityException> { callback.start(DummyState::class.java) }
            callback.stop()
        }
    }
}