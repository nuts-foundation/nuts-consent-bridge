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

import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.util.*
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Base class for NutsEventListener/Publisher, handles all connection logic
 */
abstract class NutsEventBase {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var consentBridgeNatsProperties: ConsentBridgeNatsProperties

    lateinit var cf: StreamingConnectionFactory
    var connection: StreamingConnection? = null

    /**
     * Initializes the connection to the Nats streaming server and creates a subscription to channel with subject: "consentRequest"
     *
     * It uses the standard SubscriptionOptions. Server config is loaded via Spring properties: nuts.consent.nats.*.
     *
     * The subscription receives all events but only processes: ["consentRequest constructed", "consentRequest in flight", "consentRequest in flight for final state", "all signatures present", "attachment signed"]
     */
    @PostConstruct
    fun init() {
        logger.debug("Connecting listener to Nats on ${consentBridgeNatsProperties.address} with ClusterID: ${consentBridgeNatsProperties.cluster}")

        cf = StreamingConnectionFactory(consentBridgeNatsProperties.cluster, "cordaBridgePublisher-${Integer.toHexString(Random().nextInt())}")

        val listener = ConnectionListener { conn, type ->
            when(type) {
                ConnectionListener.Events.CONNECTED -> {
                    cf.natsConnection = conn
                    connection = cf.createConnection() // this reuses the just created Nats connection but as StreamingConnection
                    initListener()

                    logger.info("EventListener connected to Nats server")
                }
                ConnectionListener.Events.CLOSED -> logger.info("Nats connection to EventListener closed")
                ConnectionListener.Events.DISCONNECTED -> logger.trace("Nats disconnected")
                ConnectionListener.Events.RECONNECTED -> logger.debug("Nats reconnected")
                ConnectionListener.Events.RESUBSCRIBED -> logger.trace("Nats subscription resubscribed")
            }
        }

        val o = Options.Builder()
                .server(consentBridgeNatsProperties.address)
                .maxReconnects(-1)
                .connectionListener(listener)
                .build()
        Nats.connectAsynchronously(o, true)
    }

    /**
     * Returns the status of the connection
     */
    fun connected() : Boolean {
        return connection?.natsConnection?.status == Connection.Status.CONNECTED
    }

    /**
     * stop subscription and close Nats connection
     */
    @PreDestroy
    fun destroyBase() {
        logger.debug("Disconnecting listener from Nats")

        connection?.close()
    }

    protected abstract fun initListener()
}