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

package nl.nuts.consent.bridge.pipelines

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.DataFeed
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.BinaryComparisonOperator
import net.corda.core.node.services.vault.ColumnPredicate
import net.corda.core.node.services.vault.DEFAULT_PAGE_NUM
import net.corda.core.node.services.vault.DEFAULT_PAGE_SIZE
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.node.services.vault.SortAttribute
import nl.nuts.consent.bridge.ConsentRegistryProperties
import nl.nuts.consent.bridge.EventStoreProperties
import nl.nuts.consent.bridge.corda.CordaManagedConnection
import nl.nuts.consent.bridge.corda.CordaManagedConnectionFactory
import nl.nuts.consent.bridge.corda.CordaService
import nl.nuts.consent.bridge.corda.StateFileStorageControl
import nl.nuts.consent.bridge.events.apis.EventApi
import nl.nuts.consent.bridge.io.MasterSlaveConnection
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.NatsManagedConnection
import nl.nuts.consent.bridge.nats.NatsManagedConnectionFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import rx.Subscription
import java.io.IOException
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeoutException
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Handles State events from Corda, event listener is stopped when Nats connection is closed.
 * Stopping subscriptions is to make sure retries are not wasted on during a connection problem (just 1)
 *
 * Abstract class for servicing two types of Corda state events
 */
abstract class CordaStateChangeToNatsPipeline<S : ContractState> {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var natsManagedConnectionFactory: NatsManagedConnectionFactory
    lateinit var natsManagedConnection: NatsManagedConnection

    @Autowired
    lateinit var cordaManagedConnectionFactory: CordaManagedConnectionFactory
    lateinit var cordaManagedConnection: CordaManagedConnection

    lateinit var masterSlaveConnection: MasterSlaveConnection
    lateinit var cordaService: CordaService

    @Autowired
    lateinit var stateFileStorageControl: StateFileStorageControl

    @Autowired
    lateinit var eventstoreProperties: EventStoreProperties
    lateinit var eventApi: EventApi

    @Autowired
    lateinit var consentRegistryProperties: ConsentRegistryProperties

    var subscription: Subscription? = null

    /**
     * The name of the pipeline, also used on the name of the connections and in logging
     */
    abstract fun name(): String

    /**
     * Class object for generics
     */
    abstract fun stateClass(): Class<S>

    /**
     * callback for when a state produces event is observed
     */
    abstract fun stateProduced(stateAndRef: StateAndRef<S>)

    /**
     * callback for when a state consumed event is observed
     */
    abstract fun stateConsumed(stateAndRef: StateAndRef<S>)

    @PostConstruct
    fun init() {
        eventApi = EventApi(eventstoreProperties.url)

        natsManagedConnection = natsManagedConnectionFactory.`object`
        cordaManagedConnection = cordaManagedConnectionFactory.`object`

        masterSlaveConnection = MasterSlaveConnection(natsManagedConnection, cordaManagedConnection)

        cordaService = CordaService(cordaManagedConnection, consentRegistryProperties)

        natsManagedConnection.name = "${name()}-Publisher"
        cordaManagedConnection.name = "${name()}-Observer"
        cordaManagedConnection.onConnected = { startListeners() }
        cordaManagedConnection.onDisconnected = { stopListeners() }

        masterSlaveConnection.connect()
    }

    private fun startListeners() {
        try {
            val stateClass = stateClass()
            val stateName = name()
            val proxy = cordaManagedConnection.getConnection().proxy

            // time criteria
            var epoch = stateFileStorageControl.readTimestamp(stateName)
            if (epoch != 0L) {
                epoch -= 60000L // some overlap for parallel processing
            }

            val asOfDateTime = Instant.ofEpochMilli(epoch)

            logger.debug("Finding old produced and consumed states since $epoch")
            publishMissedProducedStates(proxy, asOfDateTime, stateClass)
            publishMissedConsumedStates(proxy, asOfDateTime, stateClass)

            // we're caught up
            val now = System.currentTimeMillis()
            stateFileStorageControl.writeTimestamp(stateName, now)

            val feed = currentFeed(proxy, Instant.ofEpochMilli(now), stateClass)

            val observable = feed.updates

            // transfer feed events to callback
            subscription = observable.subscribe({ update ->
                logger.debug("Observed ${update.type} state update")

                update.produced.forEach {
                    logger.debug("Observed produced state ${it.state.data.javaClass.name} within contract ${it.state.contract}")

                    stateProduced(it)

                    // update latest state, since any timestamp is missing from the Corda side, we store now!
                    stateFileStorageControl.writeTimestamp(stateName, System.currentTimeMillis())
                }
                update.consumed.forEach {
                    logger.debug("Observed consumed state ${it.state.data.javaClass.name} within contract ${it.state.contract}")

                    stateConsumed(it)

                    // update latest state, since any timestamp is missing from the Corda side, we store now!
                    stateFileStorageControl.writeTimestamp(stateName, System.currentTimeMillis())
                }
            },
                { e: Throwable ->
                    // todo this might stop the listeners even when connection remains, monitor for a specific exception?
                    logger.error(e.message, e)
                    logger.info("Removing corda observable")

                    // cleanup stuff to make sure we don't leak anything
                    stopListeners()
                })

            logger.debug("Started CordaStateChangeListener subscription for ${stateClass()}")
        } catch (e: IllegalStateException) {
            logger.error("Unexpected exception when starting listener: ${e.message}")
        }
    }

    private fun stopListeners() {
        subscription?.unsubscribe()
    }

    private fun currentFeed(proxy: CordaRPCOps, asOfDateTime: Instant, stateClass: Class<S>): DataFeed<Vault.Page<S>, Vault.Update<S>> {
        // feed criteria
        return proxy.vaultTrackBy(
            producedCriteria(asOfDateTime).or(consumedCriteria(asOfDateTime)),
            PageSpecification(DEFAULT_PAGE_NUM, DEFAULT_PAGE_SIZE),
            Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.CommonStateAttribute.STATE_REF), Sort.Direction.ASC))),
            stateClass)
    }

    private fun publishMissedProducedStates(proxy: CordaRPCOps, asOfDateTime: Instant, clazz: Class<S>) {
        var historyDone = false
        var tempFeed: Vault.Page<S>? = null
        while (!historyDone) {
            tempFeed = vaultQueryBy(proxy, producedCriteria(asOfDateTime), clazz)

            // update exit criteria
            historyDone = tempFeed.states.size < DEFAULT_PAGE_SIZE

            // publish all old
            tempFeed.states.forEach {
                logger.debug("Observed older produced state ${it.state.data.javaClass.name} within contract ${it.state.contract}")

                stateProduced(it)
            }
        }
    }

    private fun publishMissedConsumedStates(proxy: CordaRPCOps, asOfDateTime: Instant, clazz: Class<S>) {
        var historyDone = false
        var tempFeed: Vault.Page<S>? = null
        while (!historyDone) {
            tempFeed = vaultQueryBy(proxy, consumedCriteria(asOfDateTime), clazz)

            // update exit criteria
            historyDone = tempFeed.states.size < DEFAULT_PAGE_SIZE

            // publish all old
            tempFeed.states.forEach {
                logger.debug("Observed older consumed state ${it.state.data.javaClass.name} within contract ${it.state.contract}")

                stateConsumed(it)
            }
        }
    }

    private fun vaultQueryBy(proxy: CordaRPCOps, criteria: QueryCriteria, clazz: Class<S>) : Vault.Page<S> {
        return proxy.vaultQueryBy(
            criteria,
            PageSpecification(DEFAULT_PAGE_NUM, DEFAULT_PAGE_SIZE),
            Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.CommonStateAttribute.STATE_REF), Sort.Direction.ASC))),
            clazz)
    }

    private fun consumedCriteria(asOfDateTime: Instant) : QueryCriteria.VaultQueryCriteria {
        val consumedAfterExpression = QueryCriteria.TimeCondition(
            QueryCriteria.TimeInstantType.CONSUMED,
            ColumnPredicate.BinaryComparison(BinaryComparisonOperator.GREATER_THAN_OR_EQUAL, asOfDateTime))
        return QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL, timeCondition = consumedAfterExpression)
    }

    private fun producedCriteria(asOfDateTime: Instant) : QueryCriteria.VaultQueryCriteria {
        val recordedAfterExpression = QueryCriteria.TimeCondition(
            QueryCriteria.TimeInstantType.RECORDED,
            ColumnPredicate.BinaryComparison(BinaryComparisonOperator.GREATER_THAN_OR_EQUAL, asOfDateTime))
        return QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL, timeCondition = recordedAfterExpression)
    }

    protected fun remoteEvent(stateUUID: UUID): Event {
        return eventToEvent(eventApi.getEvent(stateUUID))
    }

    private fun eventToEvent(source: nl.nuts.consent.bridge.events.models.Event): Event {
        return Event(
            UUID = source.uuid,
            payload = source.payload,
            initiatorLegalEntity = source.initiatorLegalEntity,
            externalId = source.externalId,
            consentId = source.consentId.toString(),
            retryCount = source.retryCount,
            error = source.error,
            name = EventName.fromString(source.name.value)
        )
    }

    @PreDestroy
    fun destroy() {
        stopListeners()

        masterSlaveConnection.terminate()
    }

    private fun resetConnection() {
        natsManagedConnection.disconnect()
        natsManagedConnection.connect()
    }

    /**
     * Publishes the given data to the given channel
     */
    protected fun publish(subject: String, data: ByteArray) {
        try {
            natsManagedConnection.getConnection().publish(subject, data) { _, e ->
                if (e != null) {
                    logger.error("Unable to publish: ${e.message}")
                    resetConnection()
                }
            }
        } catch (e: IOException) {
            logger.error("Unable to publish: ${e.message}")
            resetConnection()
        } catch (e: TimeoutException) {
            logger.error("Unable to publish: ${e.message}")
            resetConnection()
        } catch (e: IllegalStateException) {
            logger.error("Unable to publish: ${e.message}")
            resetConnection()
        }
    }
}
