/*
 * Nuts consent bridge
 * Copyright (C) 2020 Nuts community
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

import net.corda.testing.core.ALICE_NAME
import net.corda.node.services.Permissions
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import np.com.madanpokharel.embed.nats.EmbeddedNatsConfig
import np.com.madanpokharel.embed.nats.EmbeddedNatsServer
import np.com.madanpokharel.embed.nats.NatsServerConfig
import np.com.madanpokharel.embed.nats.NatsStreamingVersion
import np.com.madanpokharel.embed.nats.ServerType
import org.junit.BeforeClass
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch

abstract class NodeBasedIntegrationTest {
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

        var validProperties : ConsentBridgeRPCProperties? = null
        var node: NodeHandle? = null

        val waitForTests = CountDownLatch(1)

        var running: Boolean = false

        @BeforeClass
        @JvmStatic fun runNodes() {
            if (!running) {
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

                        // we leave everything running till the process exits
                        waitForTests.await()
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

                blockUntilSet(120000L) {
                    node
                }
                running = true
            }
        }
    }}