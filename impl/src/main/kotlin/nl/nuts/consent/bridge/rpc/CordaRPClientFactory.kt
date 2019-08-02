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

package nl.nuts.consent.bridge.rpc

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.CordaRPCConnection
import net.corda.client.rpc.RPCException
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.seconds
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import org.apache.activemq.artemis.api.core.ActiveMQSecurityException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.AbstractFactoryBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

/**
 * abstraction for creating Corda RPC client
 */
class CordaRPClientFactory : AbstractFactoryBean<CordaRPClientWrapper>() {
    @Autowired
    lateinit var consentBridgeRPCProperties: ConsentBridgeRPCProperties

    override fun createInstance(): CordaRPClientWrapper {
        return CordaRPClientWrapper(consentBridgeRPCProperties)
    }

    override fun getObjectType(): Class<*>? {
        return CordaRPCClient::class.java
    }

    override fun isSingleton(): Boolean {
        return false
    }

    override fun destroyInstance(instance: CordaRPClientWrapper?) {
        instance?.term()
    }
}

/**
 * Wrapper class for CordaRPC connections, enabling auto-reconnect
 */
class CordaRPClientWrapper : AutoCloseable {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private var connection: CordaRPCConnection? = null
    private var shutdown:Boolean = false

    private var consentBridgeRPCProperties:ConsentBridgeRPCProperties
    private var retryCount:Int

    constructor(consentBridgeRPCProperties:ConsentBridgeRPCProperties) {
        this.consentBridgeRPCProperties = consentBridgeRPCProperties
        retryCount = consentBridgeRPCProperties.retryCount
    }

    override fun close() {
        logger.info("Closing RPC connection")

        connection?.forceClose()
        connection = null
    }

    /**
     * Not only close the connection but also indicate a new connection should not be made.
     */
    fun term() {
        logger.info("Terminating RPC connection")

        shutdown = true
        close()
    }

    /**
     * get a handle to the CordaRPCOps object, also connects if needed
     * @return handle to CordaRPCOps
     */
    @Synchronized fun proxy() : CordaRPCOps? {
        if (shutdown) {
            throw IllegalStateException("Request for proxy when shutdown is in progress")
        }

        if (connection == null) {
            connect()
        }

        return connection?.proxy
    }

    private fun connect() {
        if (shutdown) {
            return
        }

        val retryInterval = consentBridgeRPCProperties.retryIntervalSeconds.seconds
        val nodeAddress = NetworkHostAndPort(consentBridgeRPCProperties.host, consentBridgeRPCProperties.port)

        do {
            connection = try {
                logger.info("Connecting to: $nodeAddress")
                val client = CordaRPCClient(
                        nodeAddress,
                        object : CordaRPCClientConfiguration() {
                            override val connectionMaxRetryInterval = retryInterval
                        }
                )
                val unvalidatedConnection = client.start(consentBridgeRPCProperties.user, consentBridgeRPCProperties.password)

                // Check connection is truly operational before returning it.
                val nodeInfo = unvalidatedConnection.proxy.nodeInfo()
                require(nodeInfo.legalIdentitiesAndCerts.isNotEmpty())
                unvalidatedConnection
            } catch(secEx: ActiveMQSecurityException) {
                // Incorrect credentials, log and rethrow
                logger.error(secEx.message)
                throw secEx
            } catch(ex: RPCException) {
                // Deliberately not logging full stack trace as it will be full of internal stacktraces.
                logger.error("Exception upon establishing connection: " + ex.message)
                retryCount--
                if (retryCount == 0) {
                    shutdown = true
                }
                null
            }

            if(connection != null) {
                logger.info("Connection successfully established with: $nodeAddress")
            }
            // Could not connect this time round - pause before giving another try.
            Thread.sleep(retryInterval.toMillis())
        } while (!shutdown && connection == null)
    }
}

/**
 * Spring configuration for registering/creating CordaRPClientFactory and CordaRPClientWrapper beans.
 */
@Configuration
class CordaRPCClientConfiguration {
    @Bean
    fun cordaRPCClientFactory() : CordaRPClientFactory {
        return CordaRPClientFactory()
    }

    @Bean
    fun cordaRPClientWrapper() : CordaRPClientWrapper {
        return cordaRPCClientFactory().getObject()
    }
}
