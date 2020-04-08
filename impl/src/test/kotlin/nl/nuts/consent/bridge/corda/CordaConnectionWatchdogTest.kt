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

package nl.nuts.consent.bridge.corda

import com.nhaarman.mockito_kotlin.mock
import net.corda.client.rpc.RPCException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class CordaConnectionWatchdogTest {

    lateinit var cordaManagedConnection: CordaManagedConnection
    lateinit var cordaConnectionWatchdog: CordaManagedConnection.CordaConnectionWatchdog
    lateinit var latch: CountDownLatch

    var connected = false
    var disconnected = false
    var error = false

    @Before
    fun setup() {
        latch = CountDownLatch(1)
        cordaManagedConnection = mock()
        cordaConnectionWatchdog = CordaManagedConnection.CordaConnectionWatchdog(cordaManagedConnection, 5L, ::onConnected, ::onDisconnected, ::onError)
    }

    @Test
    fun `exception on connect sets noConnectionReason`() {
        `when`(cordaManagedConnection.connectToCorda()).thenThrow(RPCException("reason"))

        runTillReason("reason")
        Thread.sleep(2L)

        assertTrue(error)
    }

    @Test
    fun `successful connect calls onConnected`() {
        `when`(cordaManagedConnection.connectToCorda()).thenReturn(mock())

        runTillReason(null)
        Thread.sleep(2L)

        assertTrue(connected)
    }

    @Test
    fun `disconnect on failing to retrieve nodeInfo`() {
        `when`(cordaManagedConnection.connectToCorda()).thenReturn(mock())

        runTillReason("Corda RPC connection lost for Mock for CordaManagedConnection")
        Thread.sleep(2L)

        assertTrue(disconnected)
    }

    @After
    fun teardown() {
        cordaConnectionWatchdog.terminate()
        if (latch.count == 1L) {
            latch.countDown()
        }
    }

    private fun onConnected() {
        connected = true
    }

    private fun onDisconnected() {
        disconnected = true
    }

    private fun onError() {
        error = true
    }

    private fun runTillReason(reason: String?) {
        run()

        var r = true

        Thread {
            while(r) {
                if (reason == cordaConnectionWatchdog.noConnectionReason ||
                    (reason != null && cordaConnectionWatchdog.noConnectionReason != null && cordaConnectionWatchdog.noConnectionReason!!.startsWith(reason))
                ) {
                    cordaConnectionWatchdog.terminate()
                    latch.countDown()
                    break
                }
                Thread.sleep(2L)
            }
        }.start()

        latch.await(100, TimeUnit.MILLISECONDS)
        r = false
    }

    private fun run() {
        Thread(cordaConnectionWatchdog).start()
    }
}