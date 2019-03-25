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

package nl.nuts.consent.bridge.zmq

import nl.nuts.consent.bridge.ConsentBridgeZMQProperties
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit4.SpringRunner
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@RunWith(SpringRunner::class)
class RouterIntegrationTest {

    @Autowired
    lateinit var consentBridgeZMQProperties: ConsentBridgeZMQProperties

    @Autowired
    lateinit var context: ZContext

    @Autowired
    lateinit var router: Router

    @Test
    fun `ZMQ context is initialised`() {
        assertNotNull(context)
    }

    @Test
    fun `router responds with ACK`() {
        // lets try and connect a client
        context.createSocket(ZMQ.REQ).use {
            it.connect("tcp://localhost:${consentBridgeZMQProperties.routerPort}")
            it.send("0-0-0")
            assertEquals("ACK", it.recvStr())
        }
    }

    @Test
    fun `multiple clients can connect at the same time`() {
        val threads = mutableListOf<Thread>()
        val rand = Random()

        val results = mutableListOf<String>()

        repeat(4) {i ->
            threads.add(Thread(Runnable {
                Thread.sleep(rand.nextInt(100) + 1L)
                repeat(5) {
                    context.createSocket(ZMQ.REQ).use {
                        it.connect("tcp://localhost:${consentBridgeZMQProperties.routerPort}")
                        it.send("$i-0-0")
                        results.add(it.recvStr())
                    }
                }
            }))
        }

        threads.map(Thread::start)
        threads.map(Thread::join)
        assertTrue(results.all { it == "ACK" })
        assertEquals(20, results.size)
    }
}