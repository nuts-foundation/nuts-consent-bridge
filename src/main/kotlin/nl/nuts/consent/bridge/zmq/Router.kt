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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.zeromq.ZContext
import org.zeromq.ZMQ
import org.zeromq.ZMQException
import org.zeromq.ZThread
import zmq.ZError
import java.nio.channels.ClosedChannelException
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Single service which binds a ZeroMQ Router to a port. Clients connect using a ZeroMQ REQ socket and send:
 *  - ZeroMQ topic used on the subscriber socket
 *  - Corda Consent State to subscribe to
 *  - Offset timestamp (epoch) as starting point for the subscription
 */
@Service
class Router {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var consentBridgeZMQProperties: ConsentBridgeZMQProperties

    @Autowired
    lateinit var context: ZContext

    private lateinit var routerController: RouterController
    /**
     * Setup the ZeroMQ socket
     */
    @PostConstruct
    fun init() {
        routerController =  RouterController(consentBridgeZMQProperties.routerPort, context)

        logger.debug("Starting router controller on ${consentBridgeZMQProperties.routerPort}")

        ZThread.start(routerController)
    }

    /**
     * Close down the REQ/ROUTER socket
     */
    @PreDestroy
    fun destroy() {
        routerController.stop()
    }

    /**
     * Main receive loop for Router
     */
    private class RouterController(private val port: Int, private val context: ZContext) : ZThread.IDetachedRunnable {

        private val logger: Logger = LoggerFactory.getLogger(this::class.java)

        private lateinit var socket: ZMQ.Socket

        private var interruptLoop = false

        override fun run(args: Array<out Any>?) {
            socket = context.createSocket(ZMQ.ROUTER)
            socket.bind("tcp://*:$port")

            logger.info("RouterController bound to port: $port")

            while (!interruptLoop) {
                try {
                    // for non-blocking thread
                    Thread.sleep(10)

                    // identity frame
                    val identity = socket.recv(ZMQ.NOBLOCK) ?: continue

                    // empty frame
                    socket.recv()
                    // data frame
                    val data = socket.recvStr()

                    logger.debug("RouterController received $data")

                    // reply with ACK
                    socket.sendMore(identity)
                    socket.sendMore("")
                    socket.send("ACK")
                } catch(e:Exception) {
                    // message is not ready yet, do not block, for ZMQException
//                    if (e.errorCode == ZError.EAGAIN) {
//                        continue
//                    }
                    logger.error("RouterController received exception: ${e.message}, destroying socket")
                    break
                }
            }

            context.destroySocket(socket)
        }

        fun stop() {
            interruptLoop = true
        }
    }
}

@Configuration
class ContextFactory {

    /**
     * Create ZMQContext instance
     */
    @Bean
    fun getZContext() : ZContext {
        return ZContext()
    }
}