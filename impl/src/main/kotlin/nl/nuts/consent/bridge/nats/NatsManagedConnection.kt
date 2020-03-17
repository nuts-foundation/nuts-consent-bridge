/*
 * Nuts consent bridge
 * Copyright (C) 2020 Nuts community
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
import net.corda.core.utilities.seconds
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.Constants
import nl.nuts.consent.bridge.io.EventedConnection
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Handles connection to Nats, basically propegates underlying the connection events
 */
class NatsManagedConnection(val consentBridgeNatsProperties: ConsentBridgeNatsProperties): EventedConnection<StreamingConnection>() {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private var connection: StreamingConnection? = null

    var cf: StreamingConnectionFactory? = null
    var listener: ConnectionListener
    var terminated = false
    var name: String = "unknown nats"

    init {
        listener = ConnectionListener { conn, type ->
            when(type) {
                ConnectionListener.Events.CONNECTED -> {
                    onConnected(conn)
                }
                ConnectionListener.Events.CLOSED -> logger.info("$name connection closed")
                ConnectionListener.Events.DISCONNECTED -> {
                    onDisconnected()
                    logger.trace("Nats disconnected")
                }
                ConnectionListener.Events.RECONNECTED -> {
                    onConnected(conn)
                }
                ConnectionListener.Events.RESUBSCRIBED -> logger.trace("Nats subscription resubscribed")
            }
        }
    }

    private fun onConnected(conn : Connection) {
        logger.info("$name connected to Nats server")
        try {
            cf?.natsConnection = conn

            // only create connection once, from there auto-reconnect handles all
            if (connection == null) {
                connection = cf?.createConnection()
            }
            this.onConnected()
        } catch (e: Exception) {
            logger.error(e.message, e)
            throw e
        }
    }

    fun getConnection() : StreamingConnection {
        if (connection == null) {
            throw IllegalStateException()
        }
        return connection!!
    }

    override fun disconnect() {
        logger.debug("Closing $name connection")

        // NOP just signal to stop subscriptions via onDisconnected
        // connection?.close()

        logger.info("$name closed")
        this.onDisconnected()
    }

    override fun connect() {
        if (terminated) {
            throw java.lang.IllegalStateException("$name already terminated")
        }

        logger.debug("Connecting $name to Nats cluster ${consentBridgeNatsProperties.cluster}")
        // we only connect once
        if (cf == null) {
            cf = StreamingConnectionFactory(consentBridgeNatsProperties.cluster, "${Constants.NAME}-${name}")
            cf?.ackTimeout = 5.seconds

            val o = Options.Builder()
                .server(consentBridgeNatsProperties.address)
                .maxReconnects(consentBridgeNatsProperties.retryCount)
                .connectionListener(listener)
                .pingInterval(consentBridgeNatsProperties.retryIntervalSeconds.seconds)
                .build()

            Nats.connectAsynchronously(o, true)
        }
    }

    override fun terminate() {
        logger.debug("Terminating $name connection")

        connection?.close()
        connection = null
        terminated = true

        logger.info("$name terminated")
        this.onDisconnected()
    }
}