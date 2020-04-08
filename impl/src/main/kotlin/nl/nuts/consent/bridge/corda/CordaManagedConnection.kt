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

import net.corda.client.rpc.ConnectionFailureException
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
    private var cordaConnectionWatchdog: CordaConnectionWatchdog? = null
    //private var retryCount = consentBridgeRPCProperties.retryCount

    var name: String? = "unknown corda"

    class CordaConnectionWatchdog(var managedConnection: CordaManagedConnection, var delay: Long, var onConnected: () -> Unit, var onDisconnected: () -> Unit, var onError: () -> Unit = {}): Runnable {
        private val logger: Logger = LoggerFactory.getLogger(this::class.java)
        private var terminate = false
        private var tryToConnect = true
        private var connection: CordaRPCConnection? = null

        var noConnectionReason: String? = "starting up"
        fun pause() {
            synchronized(this) {
                tryToConnect = false
            }
        }

        fun resume() {
            synchronized(this) {
                tryToConnect = true
            }
        }

        fun terminate() {
            synchronized(this) {
                closeConnection()
                terminate = true
                tryToConnect = false
            }
        }

        fun getConnection(): CordaRPCConnection? {
            synchronized(this) {
                return connection
            }
        }

        fun closeConnection() {
            synchronized(this) {
                connection?.forceClose()
                connection = null
            }
        }

        override fun run() {
            try {
                while (!terminate) {
                    synchronized(this) {
                        if (connection != null) {
                            try {
                                val nodeInfo = connection?.proxy?.nodeInfo()
                                require(nodeInfo?.legalIdentitiesAndCerts?.isNotEmpty() ?: false)
                            } catch (e: Exception) {
                                when (e) {
                                    is IllegalArgumentException, is ConnectionFailureException, is RPCException -> {
                                        noConnectionReason = e.message
                                        logger.error("Corda RPC connection lost for $managedConnection.name: ${e.message}")
                                        connection = null
                                        onDisconnected()
                                    }
                                    else -> throw e
                                }
                            }
                        } else if (tryToConnect) {
                            try {
                                noConnectionReason = null
                                connection = managedConnection.connectToCorda()
                                logger.info("Corda RPC connection established for ${managedConnection.name}")
                                onConnected()
                            } catch (e: Exception) {
                                when (e) {
                                    is IOException, is ActiveMQSecurityException, is RPCException -> {
                                        onError()
                                        logger.debug("${e::class.simpleName}: ${e.message} while trying to connect to Corda for ${managedConnection.name}")
                                        noConnectionReason = e.message
                                    }
                                    else -> throw e
                                }
                            }
                        }
                    }
                    Thread.sleep(delay)
                }
            } catch (e: Exception) {
                logger.error("unexpected exception in watchdog for ${managedConnection.name}: ${e.message}", e)
            }
        }
    }

    fun noConnectionReason(): String? {
        return cordaConnectionWatchdog?.noConnectionReason
    }

    private fun startThread() {
        cordaConnectionWatchdog = CordaConnectionWatchdog(this, consentBridgeRPCProperties.retryIntervalSeconds.seconds.toMillis(), this.onConnected, this.onDisconnected)
        Thread(cordaConnectionWatchdog).start()
    }

    override fun disconnect() {
        synchronized(this) {
            cordaConnectionWatchdog?.pause()

            if (cordaConnectionWatchdog?.getConnection() != null) {
                logger.debug("Closing Corda RPC connection for $name")

                cordaConnectionWatchdog?.closeConnection()

                logger.info("Corda RPC connection closed for $name")
                this.onDisconnected()
            }
        }
    }

    override fun connect() {
        logger.debug("Connecting to Corda RPC for $name")
        synchronized(this) {

            if (cordaConnectionWatchdog == null) {
                startThread()
            }

            // start reconnect loop
            cordaConnectionWatchdog?.resume()
        }
    }

    override fun terminate() {
        logger.debug("Terminating Corda RPC connection for $name")

        cordaConnectionWatchdog?.terminate()

        logger.info("Corda RPC connection terminated for $name")
    }

    /**
     * Helper function to get the RPCOps from a connection
     */
    fun proxy(): CordaRPCOps {
        return getConnection().proxy
    }

    /**
     * Get the established connection or an IllegalStateException
     */
    fun getConnection() : CordaRPCConnection {
        return cordaConnectionWatchdog?.getConnection() ?: throw IllegalStateException(noConnectionReason())
    }

    fun connectToCorda(): CordaRPCConnection {
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
        return unvalidatedConnection
    }
}