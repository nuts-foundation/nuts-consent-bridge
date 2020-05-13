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

package nl.nuts.consent.bridge.diagnostics

import com.nhaarman.mockito_kotlin.mock
import net.corda.client.rpc.CordaRPCConnection
import net.corda.client.rpc.RPCConnection
import net.corda.core.concurrent.CordaFuture
import net.corda.core.flows.StateMachineRunId
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import nl.nuts.consent.bridge.SchedulerProperties
import nl.nuts.consent.bridge.corda.CordaManagedConnection
import nl.nuts.consent.flow.DiagnosticFlows
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.springframework.boot.actuate.health.Status
import org.springframework.test.util.ReflectionTestUtils
import java.lang.IllegalStateException
import java.lang.UnsupportedOperationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.test.assertEquals

class CordaFlowHealthIndicatorsTest {
    lateinit var cordaNotaryPingHealthIndicator: CordaNotaryPingHealthIndicator
    lateinit var cordaRandomPingHealthIndicator: CordaRandomPingHealthIndicator
    lateinit var cordaHealthIndicator: CordaConnectionHealthIndicator
    lateinit var schedulerProperties: SchedulerProperties
    lateinit var cordaManagedConnection: CordaManagedConnection

    @Before
    fun setup() {
        cordaNotaryPingHealthIndicator = CordaNotaryPingHealthIndicator()
        cordaRandomPingHealthIndicator = CordaRandomPingHealthIndicator()
        cordaHealthIndicator = CordaConnectionHealthIndicator()
        cordaManagedConnection = mock()
        schedulerProperties = SchedulerProperties()

        listOf(cordaNotaryPingHealthIndicator, cordaRandomPingHealthIndicator).forEach {
            ReflectionTestUtils.setField(it, "cordaManagedConnection", cordaManagedConnection)
            ReflectionTestUtils.setField(it, "schedulerProperties", schedulerProperties)
        }
        ReflectionTestUtils.setField(cordaHealthIndicator, "cordaManagedConnection", cordaManagedConnection)
    }

    @Test
    fun `live connection gives UP status`() {
        // given
        val conn: CordaRPCConnection = mock()
        `when`(cordaManagedConnection.getConnection()).thenReturn(conn)

        // then
        val h = cordaHealthIndicator.health()
        assertEquals(Status.UP, h.status)
    }

    @Test
    fun `no connection gives DOWN status`() {
        // given
        `when`(cordaManagedConnection.getConnection()).thenThrow(IllegalStateException("reason"))

        // then
        val h = cordaHealthIndicator.health()
        assertEquals(Status.DOWN, h.status)
    }

    @Test
    fun `correct notary ping returns UP status`() {
        // given
        val proxy:CordaRPCOps = mock()
        `when`(cordaManagedConnection.proxy()).thenReturn(proxy)
        `when`(proxy.startFlow(DiagnosticFlows::PingNotaryFlow)).thenReturn(pingResult(true))

        // when
        cordaNotaryPingHealthIndicator.doFlowHealthCheck()

        // then
        val h = cordaNotaryPingHealthIndicator.health()
        assertEquals(Status.UP, h.status)
    }

    @Test
    fun `incorrect notary ping returns DOWN status`() {
        // given
        val proxy:CordaRPCOps = mock()
        `when`(cordaManagedConnection.proxy()).thenReturn(proxy)
        `when`(proxy.startFlow(DiagnosticFlows::PingNotaryFlow)).thenReturn(pingResult(false))

        // when
        cordaNotaryPingHealthIndicator.doFlowHealthCheck()

        // then
        val h = cordaNotaryPingHealthIndicator.health()
        assertEquals(Status.DOWN, h.status)
        assertEquals("Operation timed out", h.details["reason"])
    }

    @Test
    fun `stale notary ping returns DOWN status`() {
        // given
        val age = floor((2 * schedulerProperties.delay + 30000)/60000.0).toInt()
        cordaNotaryPingHealthIndicator.lastCheck = PingResult(true, "",
            System.currentTimeMillis() - (2 * schedulerProperties.delay + 30000))

        // when
        val h = cordaNotaryPingHealthIndicator.health()

        // then
        assertEquals(Status.DOWN, h.status)
        assertEquals("latest successful check was ${age} minutes ago", h.details["reason"])
    }

    @Test
    fun `correct random ping returns UP status`() {
        // given
        val proxy:CordaRPCOps = mock()
        `when`(cordaManagedConnection.proxy()).thenReturn(proxy)
        `when`(proxy.startFlow(DiagnosticFlows::PingRandomFlow)).thenReturn(pingResult(true))

        // when
        cordaRandomPingHealthIndicator.doFlowHealthCheck()

        // then
        val h = cordaRandomPingHealthIndicator.health()
        assertEquals(Status.UP, h.status)
    }

    private fun pingResult(success:Boolean) : FlowHandle<Unit> {
        val cf: CordaFuture<Unit> = object : CordaFuture<Unit> {
            override fun cancel(mayInterruptIfRunning: Boolean) = true

            override fun get() {
                if (!success) {
                    throw ExecutionException("", null)
                }
            }

            override fun get(timeout: Long, unit: TimeUnit) = get()
            override fun isCancelled() = false
            override fun isDone() = false
            override fun <W> then(callback: (CordaFuture<Unit>) -> W) { }

            override fun toCompletableFuture(): CompletableFuture<Unit> {
                throw UnsupportedOperationException()
            }
        }

        return object : FlowHandle<Unit>{
            override val id: StateMachineRunId = mock()
            override val returnValue: CordaFuture<Unit> = cf

            override fun close() {
                TODO("Not yet implemented")
            }
        }
    }
}