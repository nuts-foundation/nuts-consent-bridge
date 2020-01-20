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

package nl.nuts.consent.bridge.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner

@ActiveProfiles("api")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RunWith(SpringJUnit4ClassRunner::class)
class StatusApiTest {

    @Autowired
    lateinit var statusApi: StatusApi

    @Test
    fun `status returns 200 OK`() {
        val response: ResponseEntity<String> = statusApi.getStatus()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("OK", response.body)
    }

    @Test
    fun `diagnostics returns 200 with info`() {
        val response: ResponseEntity<String> = statusApi.diagnostics()

        assertEquals(HttpStatus.OK, response.statusCode)
        // TODO: CI has some kind of timing issue/race condition causing the test to be run before the listeners connect
        assertTrue(response.body.contains("nutsEventPublisher="))
        assertTrue(response.body.contains("nutsEventListener="))
        // since no Corda node is running
        assertTrue(response.body.contains("General status=DOWN"))
        assertTrue(response.body.contains("cordaRPCClientFactory=DOWN"))
        assertTrue(response.body.contains("cordaNotary=UP"))
        assertTrue(response.body.contains("cordaRandom=UP"))
    }
}