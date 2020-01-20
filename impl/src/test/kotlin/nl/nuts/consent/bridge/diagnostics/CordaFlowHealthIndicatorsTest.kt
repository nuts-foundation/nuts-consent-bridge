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
import nl.nuts.consent.bridge.SchedulerProperties
import nl.nuts.consent.bridge.rpc.CordaService
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.springframework.boot.actuate.health.Status
import org.springframework.test.util.ReflectionTestUtils
import kotlin.math.floor
import kotlin.test.assertEquals

class CordaFlowHealthIndicatorsTest {
    lateinit var cordaNotaryHealthIndicator: CordaFlowHealthIndicators.CordaNotaryHealthIndicator
    lateinit var cordaRandomHealthIndicator: CordaFlowHealthIndicators.CordaRandomHealthIndicator
    lateinit var schedulerProperties: SchedulerProperties

    private var cordaService: CordaService = mock()

    @Before
    fun setup() {
        cordaNotaryHealthIndicator = CordaFlowHealthIndicators.CordaNotaryHealthIndicator()
        cordaRandomHealthIndicator = CordaFlowHealthIndicators.CordaRandomHealthIndicator()
        schedulerProperties = SchedulerProperties()

        listOf(cordaNotaryHealthIndicator, cordaRandomHealthIndicator).forEach {
            ReflectionTestUtils.setField(it, "cordaService", cordaService)
            ReflectionTestUtils.setField(it, "schedulerProperties", schedulerProperties)
        }
    }

    @Test
    fun `correct notary ping returns UP status`() {
        // given
        `when`(cordaService.pingNotary()).thenReturn(CordaService.PingResult(true))

        // when
        cordaNotaryHealthIndicator.ping()

        // then
        val h = cordaNotaryHealthIndicator.health()
        assertEquals(Status.UP, h.status)
    }

    @Test
    fun `incorrect notary ping returns DOWN status`() {
        // given
        `when`(cordaService.pingNotary()).thenReturn(CordaService.PingResult(false, "error"))

        // when
        cordaNotaryHealthIndicator.ping()

        // then
        val h = cordaNotaryHealthIndicator.health()
        assertEquals(Status.DOWN, h.status)
        assertEquals("error", h.details["reason"])
    }

    @Test
    fun `stale notary ping returns DOWN status`() {
        // given
        val age = floor((2 * schedulerProperties.delay + 30000)/60000.0).toInt()
        `when`(cordaService.pingNotary()).thenReturn(CordaService.PingResult(true, "", System.currentTimeMillis() - (2 * schedulerProperties.delay + 30000)))

        // when
        cordaNotaryHealthIndicator.ping()

        // then
        val h = cordaNotaryHealthIndicator.health()
        assertEquals(Status.DOWN, h.status)
        assertEquals("latest successful check was ${age} minutes ago", h.details["reason"])
    }

    @Test
    fun `correct random ping returns UP status`() {
        // given
        `when`(cordaService.pingRandom()).thenReturn(CordaService.PingResult(true))

        // when
        cordaRandomHealthIndicator.ping()

        // then
        val h = cordaRandomHealthIndicator.health()
        assertEquals(Status.UP, h.status)
    }
}