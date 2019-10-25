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
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.EventStoreProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.events.apis.EventApi
import nl.nuts.consent.bridge.events.infrastructure.ClientException
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.NATS_CONSENT_REQUEST_SUBJECT
import nl.nuts.consent.bridge.nats.NutsEventPublisher
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.rpc.CordaService
import nl.nuts.consent.state.ConsentBranch
import nl.nuts.consent.state.ConsentState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import rx.Subscription
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Class for listening to State changes in the Vault.
 * The idea is that one instance is used per State. If multiple instances are used for the same state, duplicate events will be published.
 * Each instance uses a single Corda RPC connection. These are limited, so starting up instances from threads might not be the wisest.
 */
class CordaStateChangeListener<S : ContractState>(
        val cordaRPClientWrapper: CordaRPClientWrapper,
        val producedCallback:StateCallback<S> = StateCallbacks::noOpCallback,
        val consumedCallback:StateCallback<S> = StateCallbacks::noOpCallback) {

    val logger:Logger = LoggerFactory.getLogger(this::class.java)

    private var shutdown:Boolean = false

    /**
     * This will initiate the RPC connection and start the observer stream
     */
    fun start(stateClass: Class<S>) {
        val proxy = cordaRPClientWrapper.proxy()

        if (proxy == null) {
            logger.warn("Couldn't get proxy, stopping CordaStateChangeListener")
            shutdown = true
            return
        }

        if (shutdown) {
            proxy.shutdown()
            return
        }

        // todo since last successfull received!!
        // time criteria
        val asOfDateTime = Instant.ofEpochSecond(0, 0)
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

        val observable = feed.updates

        // thread safe storage
        val retryableStateUpdatesSubscription: AtomicReference<Subscription?> = AtomicReference(null)

        // transfer feed events to callback
        val subscription = observable.subscribe( { update ->
            logger.debug("Observed ${update.type} state update")

            update.produced.forEach {
                logger.debug("Observed produced state ${it.state.data.javaClass.name} within contract ${it.state.contract}")

                producedCallback.invoke(it)
            }
            update.consumed.forEach {
                logger.debug("Observed consumed state ${it.state.data.javaClass.name} within contract ${it.state.contract}")

                consumedCallback.invoke(it)
            }
        },
        { e:Throwable ->
            logger.error(e.message, e)
            logger.info("Unsubscribing and disconnecting...")

            // cleanup stuff to make sure we don't leak anything
            retryableStateUpdatesSubscription.get()?.unsubscribe()
            cordaRPClientWrapper.close()

            // start again
            start(stateClass)
        })

        logger.debug("Started CordaStateChangeListener subscription for $stateClass")

        // store in atomic reference, so that if the callback errors, the other thread can operate on it safely
        retryableStateUpdatesSubscription.set(subscription)
    }
    /**
     * Closes the RPC connection to the Corda node
     */
    fun stop() {
        shutdown = true
    }
}

/**
 * Not a real Spring factory but needed to disconnect the NutsEventListener from the CordaStateChangeListener
 * This way it's easier to test everything. It also starts the listener in case the app is started.
 */
@Service
class CordaStateChangeListenerController {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    lateinit var requestStateListener: CordaStateChangeListener<ConsentBranch>
    lateinit var consentStateListener: CordaStateChangeListener<ConsentState>

    @Autowired
    lateinit var nutsEventPublisher: NutsEventPublisher

    @Autowired
    lateinit var consentBridgeRPCProperties: ConsentBridgeRPCProperties

    @Autowired
    lateinit var cordaService: CordaService

    @Autowired
    lateinit var eventstoreProperties: EventStoreProperties
    lateinit var eventApi: EventApi

    /**
     * Initializes the eventAPI and starts both of the corda listeners (ConsentRequestState and ConsentState) if nuts.consent.rpc.enabled == true
     */
    @PostConstruct
    fun init() {
        eventApi = EventApi(eventstoreProperties.url)
        requestStateListener = CordaStateChangeListener(cordaService.cordaRPClientWrapper(), ::handleRequestStateProduced)
        consentStateListener = CordaStateChangeListener(cordaService.cordaRPClientWrapper(), ::handleStateProducedEvent)

        if (consentBridgeRPCProperties.enabled) {
            requestStateListener.start(ConsentBranch::class.java)
            consentStateListener.start(ConsentState::class.java)
        }
    }

    /**
     * Stops both listeners
     */
    @PreDestroy
    fun destroy() {
        logger.debug("Stopping corda state change listeners")

        requestStateListener.stop()
        consentStateListener.stop()

        logger.info("Corda state change listeners stopped")
    }

    /**
     * Callback function when a ConsentRequestState has been produced. It tries to find an existing service space event based on the externalId.
     * If found it re-uses the UUID from the event. If not it publishes a new event.
     *
     * it publishes "distributed consentRequest received" events
     *
     * @param stateAndRef the StateAndRef object from Corda.
     */
    fun handleRequestStateProduced(stateAndRef: StateAndRef<ConsentBranch>) {
        logger.debug("Received produced state event from Corda: ${stateAndRef.state.data}")

        val state = stateAndRef.state.data
            
        val event = cordaService.consentBranchToEvent(state)

        // find corresponding event in Nuts event store, if not found create a new state with state == 'to be accepted'
        // the contents of the new event will be a NewConsentRequestState object as json/base64
        var knownEvent: Event? = null
        try {
            knownEvent = remoteEvent(state.linearId.id)
            logger.debug("Found existing event for: ${state.linearId.id}")
        } catch (e: ClientException) {
            logger.debug("Got new consentRequestState, generating new event")
        }

        if (knownEvent != null) {
            event.initiatorLegalEntity = knownEvent.initiatorLegalEntity
            event.retryCount = knownEvent.retryCount
        }
        event.name = EventName.EventDistributedConsentRequestReceived

        val jsonBytes = Serialization.objectMapper().writeValueAsBytes(event)
        nutsEventPublisher.publish(NATS_CONSENT_REQUEST_SUBJECT, jsonBytes)
    }

    /**
     * Callback function when a ConsentState has been produced. It tries to find an existing service space event based on the externalId.
     * If found it re-uses the UUID from the event. If not it publishes a new event.
     *
     * It publishes "consent distributed" events
     *
     * @param stateAndRef the StateAndRef object from Corda.
     */
    fun handleStateProducedEvent(stateAndRef: StateAndRef<ConsentState>) {
        logger.debug("Received consentState produced event from Corda: ${stateAndRef.state.data}")

        val state = stateAndRef.state.data

        if (state.version == 1) {
            // ignore genesis block
            return
        }

        // try to find the Tx and any consumed ConsentBranches
        val tx = stateAndRef.ref.txhash

        // if nothing is found, than it wasn't a merge that produced this state
        val consentBranchUUID = cordaService.consentBranchByTx(tx) ?: return

        val event = cordaService.consentStateToEvent(state)

        // if a branch exists than there MUST be an existing event in the event store
        var knownEvent: Event? = null
        try {
            knownEvent = remoteEvent(consentBranchUUID)
        } catch (e: ClientException) {
            // nop
        }

        if (knownEvent == null) {
            logger.error("Can't find event in event store with UUID: $consentBranchUUID")
            return
        }

        event.UUID = knownEvent.UUID
        event.name = EventName.EventConsentDistributed

        val jsonBytes = Serialization.objectMapper().writeValueAsBytes(event)
        nutsEventPublisher.publish(NATS_CONSENT_REQUEST_SUBJECT, jsonBytes)
    }

    private fun remoteEvent(stateUUID: UUID): Event {
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
}

/**
 * Alias for callback function signature
 */
typealias StateCallback<S> = (StateAndRef<S>) -> Unit

/**
 * Default callbacks
 */
object StateCallbacks {
    /**
     * No-op callback, doing nothing when event is received (default)
     */
    fun <S : ContractState> noOpCallback(state:StateAndRef<S>) = Unit
}
