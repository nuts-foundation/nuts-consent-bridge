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

package nl.nuts.consent.bridge.listener

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*
import nl.nuts.consent.bridge.rpc.CordaRPClientFactory
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
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

    private var cordaRPClientWrapper: CordaRPClientWrapper

    private var producedCallbacks = mutableListOf<(StateAndRef<S>) -> Unit>()
    private var consumedCallbacks = mutableListOf<(StateAndRef<S>) -> Unit>()

    private var epochOffset:Long

    private var shutdown:Boolean = false

    constructor(cordaRPClientWrapper: CordaRPClientWrapper, epochOffset:Long = 0) {
        this.cordaRPClientWrapper = cordaRPClientWrapper
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

        val proxy = cordaRPClientWrapper.proxy()

        if (proxy == null) {
            logger.warn("Couldn't get proxy, stopping StateChangeListener")
            shutdown = true
            return
        }

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
            cordaRPClientWrapper.close()

            // start again
            start(stateClass)
        })

        // store in atomic reference, so that if the callback errors, the other thread can operate on it safely
        retryableStateUpdatesSubscription.set(subscription)
    }
    /**
     * Closes the RPC connection to the Corda node
     */
    fun stop() {
        shutdown = true
        cordaRPClientWrapper.term()
    }
}