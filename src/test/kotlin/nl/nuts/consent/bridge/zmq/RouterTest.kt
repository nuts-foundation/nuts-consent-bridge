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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.zeromq.ZContext
import org.zeromq.ZMQ
import kotlin.test.assertEquals

class RouterTest {
    private val router = Router()

    @Before
    fun setup() {
        router.consentBridgeZMQProperties = ConsentBridgeZMQProperties()
        router.context = ZContext()

        router.init()
    }

    @After
    fun cleanup() {
        router.destroy()
    }

    @Test
    fun `Router init uses ConsentBridgeZMQProperties for opening listener socket`() {
        // check if responds
        router.context.createSocket(ZMQ.REQ).use {
            it.connect("tcp://localhost:${router.consentBridgeZMQProperties.routerPort}")
            it.send("0-0-0")
            assertEquals("ACK", it.recvStr())
        }
    }

    @Test
    fun `Client can disconnect cleanly`() {
        val clientContext = ZContext()
        val socket = clientContext.createSocket(ZMQ.REQ)
        socket.use {
            it.connect("tcp://localhost:${router.consentBridgeZMQProperties.routerPort}")
            it.send("0-0-0")
            assertEquals("ACK", it.recvStr())
        }

        clientContext.destroySocket(socket)

        assertEquals(0, clientContext.sockets.size)
        assertEquals(1, router.context.sockets.size)
    }
}