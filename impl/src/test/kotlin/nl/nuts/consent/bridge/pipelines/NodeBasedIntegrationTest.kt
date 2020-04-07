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

import net.corda.node.services.Permissions
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import org.junit.AfterClass
import org.junit.BeforeClass
import java.util.concurrent.CountDownLatch

open class NodeBasedIntegrationTest {
    companion object {
        const val USER = "user1"
        const val PASSWORD = "test"
        val rpcUser = User(USER, PASSWORD, permissions = setOf(Permissions.all()))
        var port = 4222

        val countDownLatch = CountDownLatch(1)

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

        var running: Boolean = false

        @BeforeClass
        @JvmStatic fun runNodes() {
            if (!running) {
                Thread {
                    // blocking call
                    driver(DriverParameters(
                        extraCordappPackagesToScan = listOf("nl.nuts.consent.bridge.corda.test"),
                        startNodesInProcess = true
                    )) {
                        val nodeF = startNode(providedName = ALICE_NAME, rpcUsers = listOf(rpcUser))
                        node = nodeF.get()
                        val address = node!!.rpcAddress
                        validProperties = ConsentBridgeRPCProperties(address.host, address.port, USER, PASSWORD, 1)

                        // we leave everything running till the process exits
                        countDownLatch.await()
                    }
                }.start()

                running = true

                blockUntilSet(120000) {
                    node
                }
            }
        }
    }
}
