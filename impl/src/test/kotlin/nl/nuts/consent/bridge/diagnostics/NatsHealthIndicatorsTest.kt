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
import io.nats.streaming.StreamingConnection
import net.corda.client.rpc.CordaRPCConnection
import net.corda.client.rpc.RPCConnection
import net.corda.core.concurrent.CordaFuture
import net.corda.core.flows.StateMachineRunId
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import nl.nuts.consent.bridge.SchedulerProperties
import nl.nuts.consent.bridge.corda.CordaManagedConnection
import nl.nuts.consent.bridge.nats.NatsManagedConnection
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

class NatsHealthIndicatorsTest {
    lateinit var natsHealthIndicator: NatsHealthIndicator
    lateinit var natsManagedConnection: NatsManagedConnection

    @Before
    fun setup() {
        natsHealthIndicator = NatsHealthIndicator()
        natsManagedConnection = mock()

        ReflectionTestUtils.setField(natsHealthIndicator, "natsManagedConnection", natsManagedConnection)
    }

    @Test
    fun `live connection gives UP status`() {
        // given
        val conn: StreamingConnection = mock()
        `when`(natsManagedConnection.getConnection()).thenReturn(conn)

        // then
        val h = natsHealthIndicator.health()
        assertEquals(Status.UP, h.status)
    }

    @Test
    fun `no connection gives DOWN status`() {
        // given
        `when`(natsManagedConnection.getConnection()).thenThrow(IllegalStateException("reason"))

        // then
        val h = natsHealthIndicator.health()
        assertEquals(Status.DOWN, h.status)
    }
}