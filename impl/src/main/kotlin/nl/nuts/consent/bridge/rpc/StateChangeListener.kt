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
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.seconds
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import org.apache.activemq.artemis.api.core.ActiveMQSecurityException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Subscription
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Generic class for registering callbacks for State changes in the Vault.
 * The idea is that one instance is used per client per State. If multiple instances are used for the same state, duplicate events will be published.
 * Each instance uses a single Corda RPC connection. These are limited, so starting up instances from threads might not be the wisest.
 */
class StateChangeListener<S : ContractState> {

    val logger:Logger = LoggerFactory.getLogger(this::class.java)

    private var consentBridgeRPCProperties : ConsentBridgeRPCProperties
    private var connection: CordaRPCConnection? = null

    private var producedCallbacks = mutableListOf<(StateAndRef<S>) -> Unit>()
    private var consumedCallbacks = mutableListOf<(StateAndRef<S>) -> Unit>()

    private var epochOffset:Long

    private var shutdown:Boolean = false

    constructor(cordaRPCRPCProperties: ConsentBridgeRPCProperties, epochOffset:Long = 0) {
        this.consentBridgeRPCProperties = cordaRPCRPCProperties
        this.epochOffset = epochOffset
    }

    /**
     * Register a callback method for state produce events.
     *
     * @param action callback method that will be called for each produced Vault state
     */
    fun onProduced(action: (StateAndRef<S>) -> Unit) {
        producedCallbacks.add(action)
    }

    /**
     * Register a callback method for state consume events.
     *
     * @param action callback method that will be called for each consumed Vault state
     */
    fun onConsumed(action: (StateAndRef<S>) -> Unit) {
        consumedCallbacks.add(action)
    }

    /**
     * This will initiate the RPC connection and start the observer stream
     */
    fun start(stateClass: Class<S>) {
        if (shutdown) {
            return
        }

        connection = connect()
        val proxy = connection!!.proxy

        // time criteria
        val asOfDateTime = Instant.ofEpochSecond(epochOffset, 0)
        val recordedAfterExpression = QueryCriteria.TimeCondition(
                QueryCriteria.TimeInstantType.RECORDED,
                ColumnPredicate.BinaryComparison(BinaryComparisonOperator.GREATER_THAN_OR_EQUAL, asOfDateTime))
        val recordedCriteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL, timeCondition = recordedAfterExpression)

        val consumedAfterExpression = QueryCriteria.TimeCondition(
                QueryCriteria.TimeInstantType.CONSUMED,
                ColumnPredicate.BinaryComparison(BinaryComparisonOperator.GREATER_THAN_OR_EQUAL, asOfDateTime))
        val consumedCriteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL, timeCondition = consumedAfterExpression)

        // feed criteria
        val feed = proxy.vaultTrackBy(
                recordedCriteria.or(consumedCriteria),
                PageSpecification(DEFAULT_PAGE_NUM, 100),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.CommonStateAttribute.STATE_REF), Sort.Direction.ASC))),
                stateClass)

        // TODO: we're only interested in updates for now
        val observable = feed.updates

        // thread safe storage
        val retryableStateUpdatesSubscription: AtomicReference<Subscription?> = AtomicReference(null)

        // transfer feed events to callback
        val subscription = observable.subscribe( { update ->
            logger.debug("Observed ${update.type} state update")

            update.produced.forEach {
                logger.debug("Observed produced state ${it.state.javaClass.name} within contract ${it.state.contract}")

                producedCallbacks.forEach { action -> action(it) }
            }
            update.consumed.forEach {
                logger.debug("Observed consumed state ${it.state.javaClass.name} within contract ${it.state.contract}")

                consumedCallbacks.forEach { action -> action(it) }
            }
        },
        { e:Throwable ->
            logger.error(e.message)
            logger.info("Unsubscribing and disconnecting...")

            // cleanup stuff to make sure we don't leak anything
            retryableStateUpdatesSubscription.get()?.unsubscribe()
            connection?.forceClose()

            // start again
            start(stateClass)
        })

        // store in atomic reference, so that if the callback errors, the other thread can operate on it safely
        retryableStateUpdatesSubscription.set(subscription)
    }

    /**
     * Connect to Corda node with a 5 second (default) delay between attempts
     */
    private fun connect() : CordaRPCConnection? {
        if (shutdown) {
            return null
        }

        val retryInterval = consentBridgeRPCProperties.retryIntervalSeconds.seconds
        val nodeAddress = NetworkHostAndPort(consentBridgeRPCProperties.host, consentBridgeRPCProperties.port)

        do {
            val connection = try {
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
                null
            }

            if(connection != null) {
                logger.info("Connection successfully established with: $nodeAddress")
                return connection
            }
            // Could not connect this time round - pause before giving another try.
            Thread.sleep(retryInterval.toMillis())
        } while (!shutdown && connection == null)

        return null
    }
    /**
     * Closes the RPC connection to the Corda node
     */
    fun terminate() {
        shutdown = true
        connection?.forceClose()
    }
}