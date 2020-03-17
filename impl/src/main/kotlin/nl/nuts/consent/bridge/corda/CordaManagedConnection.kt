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

package nl.nuts.consent.bridge.corda

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.CordaRPCConnection
import net.corda.client.rpc.RPCException
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.seconds
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.io.EventedConnection
import org.apache.activemq.artemis.api.core.ActiveMQSecurityException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Class that handles the Corda connection.
 */
class CordaManagedConnection(val consentBridgeRPCProperties: ConsentBridgeRPCProperties) : EventedConnection<CordaRPCConnection>() {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private var connection: CordaRPCConnection? = null
    private var tryToConnect:Boolean = false
    private var terminate:Boolean = false
    private var retryCount = consentBridgeRPCProperties.retryCount
    private var noConnectionReason: String? = "starting up"
    private var watchdog: Thread? = null

    var name: String = "unknown corda"

    private fun startThread() {
        watchdog = Thread {
            while(!terminate) {
                synchronized(this) {
                    if (connection != null) {
                        try {
                            val nodeInfo = connection?.proxy?.nodeInfo()
                            require(nodeInfo?.legalIdentitiesAndCerts?.isNotEmpty() ?: false)
                        } catch (e: IllegalArgumentException) {
                            noConnectionReason = e.message
                            logger.error("Corda RPC connection lost for $name: ${e.message}")
                            connection = null
                            this.onDisconnected()
                        }
                    } else if (tryToConnect) {
                        try {
                            connectToCorda()
                            logger.info("Corda RPC connection established for $name")
                            this.onConnected()
                        } catch (e: IOException) {
                            noConnectionReason = e.message // if host is unreachable
                        } catch (secEx: ActiveMQSecurityException) {
                            // Incorrect credentials
                            noConnectionReason = secEx.message
                        } catch (ex: RPCException) {
                            noConnectionReason = ex.message
                        }
                    }
                }
                Thread.sleep(consentBridgeRPCProperties.retryIntervalSeconds.seconds.toMillis())
            }
        }
        watchdog?.start()
    }

    override fun disconnect() {
        synchronized(this) {
            if (tryToConnect || connection != null) {
                logger.debug("Closing Corda RPC connection for $name")

                // stop reconnect loop
                tryToConnect = false

                connection?.forceClose()
                connection = null

                logger.info("Corda RPC connection closed for $name")
                this.onDisconnected()
            }
        }
    }

    override fun connect() {
        logger.debug("Connecting to Corda RPC for $name")
        synchronized(this) {

            if (watchdog == null) {
                startThread()
            }

            // start reconnect loop
            tryToConnect = true
        }
    }

    override fun terminate() {
        logger.debug("Terminating Corda RPC connection for $name")

        terminate = true
        disconnect()

        logger.info("Corda RPC connection terminated for $name")
    }

    fun proxy(): CordaRPCOps {
        return getConnection().proxy
    }

    fun getConnection() : CordaRPCConnection {
        if (connection == null) {
            throw IllegalStateException(noConnectionReason)
        }
        return connection!!
    }

    private fun connectToCorda() {
        val nodeAddress = NetworkHostAndPort(consentBridgeRPCProperties.host, consentBridgeRPCProperties.port)

        // first try socket, because RPC client will hang indefinitely
        val clientSocket = Socket()
        clientSocket.connect(
            InetSocketAddress(consentBridgeRPCProperties.host, consentBridgeRPCProperties.port),
            consentBridgeRPCProperties.retryIntervalSeconds.seconds.toMillis().toInt()
        )
        clientSocket.close()

        logger.debug("Connecting to: $nodeAddress for $name")

        val client = CordaRPCClient(
            nodeAddress,
            object : CordaRPCClientConfiguration() {
                override val maxReconnectAttempts = 1
            }
        )
        val unvalidatedConnection = client.start(
            username = consentBridgeRPCProperties.user,
            password = consentBridgeRPCProperties.password,
            gracefulReconnect = null
        )

        // Check connection is truly operational before returning it.
        val nodeInfo = unvalidatedConnection.proxy.nodeInfo()
        require(nodeInfo.legalIdentitiesAndCerts.isNotEmpty())

        // suc6
        connection = unvalidatedConnection
        noConnectionReason = null
    }
}