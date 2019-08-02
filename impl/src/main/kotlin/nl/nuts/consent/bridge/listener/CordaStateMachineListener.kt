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

import net.corda.core.messaging.StateMachineUpdate
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.EventStateStore
import nl.nuts.consent.bridge.nats.NATS_CONSENT_REQUEST_SUBJECT
import nl.nuts.consent.bridge.nats.NutsEventPublisher
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.rpc.CordaService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import rx.Subscription
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Listener for handling Corda StateMachine updates.
 *
 * Listening to state machine updates (as opposed to state updates) is needed to inform the event store of errored states.
 * This is purely for feedback/debug purposes: "where did my request go?"
 */
class CordaStateMachineListener(
        val cordaRPClientWrapper: CordaRPClientWrapper,
        val eventPublisher: NutsEventPublisher,
        val eventStateStore: EventStateStore) {

    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private var shutdown:Boolean = false

    /**
     * This will initiate the RPC connection and start the observer stream
     */
    fun start() {
        if (shutdown) {
            return
        }

        val proxy = cordaRPClientWrapper.proxy()

        if (proxy == null) {
            logger.warn("Couldn't get proxy, stopping CordaStateMachineChangeListener")
            shutdown = true
            return
        }

        val feed = proxy.stateMachinesFeed()

        val observable = feed.updates

        // thread safe storage
        val retryableStatemachineUpdatesSubscription: AtomicReference<Subscription?> = AtomicReference(null)

        // transfer feed events to callback
        val subscription = observable.subscribe( { update ->
            val event = eventStateStore.get(update.id.uuid)

            if (event == null) {
                logger.debug("Ignoring ${update.id} state machine update")
            } else {
                logger.debug("Processing ${update.id} state machine update")
                if (update is StateMachineUpdate.Removed) {
                    update.result.doOnFailure(Consumer {
                        event.name = EventName.EventConsentRequestFlowErrored

                        val os = ByteArrayOutputStream()
                        val pw = PrintWriter(os)
                        pw.println(it.message)
                        it.printStackTrace(pw)
                        pw.close()
                        os.close()
                        event.error = os.toString()

                        // publish with new state
                        val jsonBytes = Serialization.objectMapper().writeValueAsBytes(event)
                        eventPublisher.publish(NATS_CONSENT_REQUEST_SUBJECT, jsonBytes)
                    })
                    update.result.doOnSuccess(Consumer {
                        // update event to suc6 status
                        // event.name = EventName.EventConsentRequestFlowSuccess
                        // nop
                    })

                    // finally remove
                    eventStateStore.remove(update.id.uuid)
                }
            }
        },
                { e:Throwable ->
                    logger.error(e.message)
                    logger.info("Unsubscribing and disconnecting...")

                    // cleanup stuff to make sure we don't leak anything
                    retryableStatemachineUpdatesSubscription.get()?.unsubscribe()
                    cordaRPClientWrapper.close()

                    // start again
                    start()
                })

        // store in atomic reference, so that if the callback errors, the other thread can operate on it safely
        retryableStatemachineUpdatesSubscription.set(subscription)
    }
    /**
     * Closes the RPC connection to the Corda node
     */
    fun stop() {
        shutdown = true
    }
}

/**
 * Wrapper for connecting listener to Spring lifecycle management
 */
@Service
class CordaStateMachineListenerController {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    lateinit var cordaStateMachineListener: CordaStateMachineListener

    @Autowired
    lateinit var cordaService:CordaService

    @Autowired
    lateinit var consentBridgeRPCProperties: ConsentBridgeRPCProperties

    @Autowired
    lateinit var nutsEventPublisher: NutsEventPublisher

    @Autowired
    lateinit var eventStateStore: EventStateStore

    /**
     * Starts the stateMachine listener if nuts.consent.rpc.enabled = true
     */
    @PostConstruct
    fun init() {
        cordaStateMachineListener = CordaStateMachineListener(cordaService.cordaRPClientWrapper(), nutsEventPublisher, eventStateStore)

        if (consentBridgeRPCProperties.enabled) {
            cordaStateMachineListener.start()
        }
    }

    /**
     * Stops the stateMachine listener
     */
    @PreDestroy
    fun destroy() {
        logger.debug("Stopping corda state machine listener")

        cordaStateMachineListener.stop()

        logger.info("Corda state machine listener stopped")
    }
}