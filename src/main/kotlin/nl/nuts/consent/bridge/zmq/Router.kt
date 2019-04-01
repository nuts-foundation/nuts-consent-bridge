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
import org.zeromq.*
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Single service which binds a ZeroMQ Router to a port. Clients connect using a ZeroMQ REQ socket and send:
 *  - ZeroMQ topic used on the subscriber socket
 *  - Offset timestamp (epoch) as starting point for the subscription
 *
 *  This will start a subscription for ConsentRequestState changes
 */
@Service
class Router {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var consentBridgeZMQProperties: ConsentBridgeZMQProperties

    @Autowired
    lateinit var context: ZContext

    @Autowired
    lateinit var publisher: Publisher

    private lateinit var routerController: RouterController
    /**
     * Setup the ZeroMQ socket
     */
    @PostConstruct
    fun init() {
        routerController =  RouterController(consentBridgeZMQProperties.routerPort, context, publisher)

        logger.info("Starting router controller on ${consentBridgeZMQProperties.routerPort}")

        ZThread.start(routerController)
    }

    /**
     * Close down the REQ/ROUTER socket
     */
    @PreDestroy
    fun destroy() {
        routerController.stop()

        context.destroy()
    }

    /**
     * Main receive loop for Router. It waits for request queues. Clients should send a topicId frame and an epoch frame
     *
     * 1 - topicId
     * 2 - timestamp (epoch)
     */
    private class RouterController(private val port: Int, private val context: ZContext, private val publisher: Publisher) : ZThread.IDetachedRunnable {

        private val logger: Logger = LoggerFactory.getLogger(this::class.java)

        private lateinit var socket: ZMQ.Socket

        private var shutdown = false

        override fun run(args: Array<out Any>?) {
            socket = context.createSocket(SocketType.ROUTER)
            if(!socket.bind("tcp://*:$port")) {
                throw IllegalStateException("RouterController cannot bind to $port")
            }

            logger.info("RouterController bound to port: $port")

            while (!shutdown) {
                try {
                    // for non-blocking thread
                    Thread.sleep(10)

                    // identity frame
                    val identity = socket.recv(ZMQ.NOBLOCK) ?: continue

                    // empty frame
                    socket.recv()
                    // data frame
                    val topicId = socket.recvStr()
                    val epoch = socket.recvStr().toLong()

                    logger.debug("RouterController received request for publishing to $topicId from $epoch")

                    // create subscription and send to publisher
                    publisher.addSubscription(Subscription(topicId, epoch))

                    // reply with ACK
                    socket.sendMore(identity)
                    socket.sendMore("")
                    socket.send("ACK")
                } catch(e:Exception) {
                    logger.error("RouterController received exception: ${e.message}, destroying socket")
                    break
                }
            }

            logger.info("RouterController stopped")

            context.destroySocket(socket)
        }

        fun stop() {
            shutdown = true

            logger.debug("Stopping routerController")
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