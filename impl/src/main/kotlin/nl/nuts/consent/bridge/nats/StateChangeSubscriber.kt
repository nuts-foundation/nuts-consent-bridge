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

package nl.nuts.consent.bridge.nats

import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import net.corda.core.crypto.newSecureRandom
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.listener.StateChangeListener
import nl.nuts.consent.bridge.listener.StateChangeListenerFactory
import nl.nuts.consent.state.ConsentRequestState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy


object StateChange {

    @Service
    class Bootstrap {
        private val logger: Logger = LoggerFactory.getLogger(this::class.java)

        @Autowired
        lateinit var consentBridgeNatsProperties: ConsentBridgeNatsProperties

        @Autowired
        lateinit var stateChangeListenerFactory: StateChangeListenerFactory

        lateinit var cf: StreamingConnectionFactory
        lateinit var stateChangeSubscriber: StateChangeSubscriber

        @PostConstruct
        fun init() {
            cf = StreamingConnectionFactory(consentBridgeNatsProperties.cluster, "cordaBridgePublisher2")
            cf.natsUrl = consentBridgeNatsProperties.address

            val listener = stateChangeListenerFactory.createInstance<ConsentRequestState>()
            stateChangeSubscriber = StateChangeSubscriber(listener, cf)
            stateChangeSubscriber.start()
        }

        @PreDestroy
        fun destroy() {
            logger.debug("Stopping publisher")

            stateChangeSubscriber.stop()

            logger.info("Publisher stopped")
        }
    }

    /**
     * From the subscriber we receive a topic, type of state change to listen to and the timestamped head of the stream
     * For every published message, we send a nats event
     *
     * if publishing fails, the listener will stop and the event log/store should be used for retrying.
     */
    class StateChangeSubscriber(val listener: StateChangeListener<ConsentRequestState>, val cf: StreamingConnectionFactory) {
        private val logger: Logger = LoggerFactory.getLogger(this::class.java)

        lateinit var connection: StreamingConnection

        fun start() {
            logger.debug("Connecting publisher to Nats on ${cf.natsUrl} with ClusterID: ${cf.clusterId}")

            connection = cf.createConnection()

            logger.info("Publisher connected to Nats on ${cf.natsUrl} with ClusterID: ${cf.clusterId}")

            listener.onProduced {
                try {

                } catch (e: Exception) {
                    logger.error("StateChangeSubscriber failed to publish due to error: ${e.message}")
                    stop()
                }
            }
            listener.onConsumed {
                try {

                } catch (e: Exception) {
                    logger.error("StateChangeSubscriber failed to publish due to error: ${e.message}")
                    stop()
                }
            }
            // TODO: support other classess as well.
            listener.start(ConsentRequestState::class.java)

            logger.info("Started StateChangeSubscriber for ConsentRequestState")
        }

        fun stop(): Boolean {
            try {
                listener.stop()
            } catch (e: Exception) {
                logger.error("Could stop listener due to error: ${e.message}")
            }

            try {
                connection.close()
            } catch (e: Exception) {
                logger.error("Could stop listener due to error: ${e.message}")
            }

            logger.info("Stopped StateChangeSubscriber for ConsentRequestState")

            return true
        }
    }
}