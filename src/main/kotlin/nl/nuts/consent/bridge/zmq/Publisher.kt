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

import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.ConsentBridgeZMQProperties
import nl.nuts.consent.bridge.rpc.StateChangeListener
import nl.nuts.consent.state.ConsentRequestState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.zeromq.*
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Control class for linking StateChangeListener to publisher topics.
 */
@Service
class Publisher {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val subscribers = HashMap<Subscription, StateChangeSubscriber>()

    @Autowired
    lateinit var consentBridgeZMQProperties: ConsentBridgeZMQProperties

    @Autowired
    lateinit var consentBridgeRPCProperties: ConsentBridgeRPCProperties

    @Autowired
    lateinit var context: ZContext

    @PostConstruct
    fun init() {
        logger.debug("Starting publisher on ${consentBridgeZMQProperties.publisherAddress}")

        ZThread.start(ZThread.IDetachedRunnable {
            val publishSocket = context.createSocket(SocketType.XPUB)
            publishSocket.bind(consentBridgeZMQProperties.publisherAddress)

            val dealerSocket = context.createSocket(SocketType.XSUB)
            dealerSocket.bind("inproc://proxy")

            logger.info("Starting ZMQ proxy from ${consentBridgeZMQProperties.publisherAddress} to inproc://proxy")

            ZMQ.proxy(publishSocket, dealerSocket, null)
        })

        logger.info("Started publisher thread")
    }

    @PreDestroy
    fun destroy() {
        logger.info("Stopping publisher bound to ${consentBridgeZMQProperties.publisherAddress}")

        subscribers.map {p -> removeSubscription(p.key)}
    }

    fun addSubscription(subscription: Subscription) {
        // reset any live subscription
        removeSubscription(subscription)

        val subscriber = StateChangeSubscriber(subscription, context, consentBridgeRPCProperties)

        // add to bookkeeping
        subscribers[subscription] = subscriber

        // startup listener
        subscriber.start()
    }

    fun removeSubscription(subscription: Subscription) {
        subscribers[subscription]?.term()
        subscribers.remove(subscription)
    }

    /**
     * From the subscriber we receive a topic, type of state change to listen to and the timestamped head of the stream
     * For every publish message, we send a single frame:
     * <topic>:<stateClass>:<stateLinearId>:<operation>
     *
     * if publishing fails, the listener will stop. But the entry will still be there. We don't record the point at which we should resume,
     * so for now: nothing. Hopefully the client has noticed the same and is resuming for us (with same topic id).
     */
    private class StateChangeSubscriber(val subscription:Subscription, val context: ZContext, val consentBridgeRPCProperties: ConsentBridgeRPCProperties) {
        private var listener: StateChangeListener<ConsentRequestState>? = null

        private val logger: Logger = LoggerFactory.getLogger(this::class.java)

        var responseSocket : ZMQ.Socket? = null

        fun start() {
            responseSocket = context.createSocket(SocketType.PUB)
            responseSocket!!.connect("inproc://proxy")

            listener = StateChangeListener(consentBridgeRPCProperties, subscription.offset)
            listener!!.onProduced {
                try {
                    responseSocket!!.send("${subscription.topicId}:ConsentRequestState:${it.state.data}:produced")
                } catch (e:ZMQException) {
                    logger.error("StateChangeSubscriber failed to publish due to error: ${e.message}")
                    stop()
                }
            }
            listener!!.onConsumed {
                try {
                    responseSocket!!.send("${subscription.topicId}:ConsentRequestState:${it.state.data}:consumed")
                } catch (e:ZMQException) {
                    logger.error("StateChangeSubscriber failed to publish due to error: ${e.message}")
                    stop()
                }
            }
            // TODO: support other classess as well.
            listener!!.start(ConsentRequestState::class.java)

            logger.info("Started StateChangeSubscriber with topicId: ${subscription.topicId}")
        }

        fun term() : Boolean {
            try {
                responseSocket?.send(ZMQ.PROXY_TERMINATE)
            } catch(e:Exception) {
                logger.debug("Could send PROXY_TERMINATE due to error: ${e.message}")
            }
            return stop()
        }

        fun stop() : Boolean {
            try {
                listener?.terminate()
                context.destroySocket(responseSocket!!)
            } catch (e:Exception) {
                logger.debug("Could terminate listener/destroy socket due to error: ${e.message}")
            }

            logger.info("Stopped StateChangeSubscriber with topicId: ${subscription.topicId}")

            return true
        }
    }
}

/**
 * Converted data read from ZMQ socket
 */
data class Subscription(val topicId:String, val offset:Long)