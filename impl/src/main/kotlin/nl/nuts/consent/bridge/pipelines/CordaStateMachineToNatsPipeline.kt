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

import net.corda.core.messaging.StateMachineUpdate
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.io.MasterSlaveConnection
import nl.nuts.consent.bridge.nats.EventStateStore
import nl.nuts.consent.bridge.nats.NatsManagedConnection
import nl.nuts.consent.bridge.corda.CordaManagedConnection
import nl.nuts.consent.bridge.corda.CordaManagedConnectionFactory
import nl.nuts.consent.bridge.corda.CordaService
import nl.nuts.consent.bridge.nats.NatsManagedConnectionFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import rx.Subscription
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.util.function.Consumer
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Handles state machine events from Corda, event listener is stopped when Nats connection is closed.
 * Stopping subscriptions is to make sure retries are not wasted on during a connection problem (just 1)
 */
@Service
class CordaStateMachineToNatsPipeline {
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
    lateinit var eventStateStore: EventStateStore

    var subscription: Subscription? = null

    /**
     * Setup the masterSlave connection manager
     */
    @PostConstruct
    fun init() {
        natsManagedConnection = natsManagedConnectionFactory.`object`
        cordaManagedConnection = cordaManagedConnectionFactory.`object`

        masterSlaveConnection = MasterSlaveConnection(natsManagedConnection, cordaManagedConnection)

        cordaManagedConnection.name = "smObserver"
        natsManagedConnection.name = "smPublisher"
        cordaManagedConnection.onConnected = { startListeners() }
        cordaManagedConnection.onDisconnected = { stopListeners() }

        masterSlaveConnection.connect()
    }

    private fun startListeners() {
        try {
            val feed = cordaManagedConnection.getConnection().proxy.stateMachinesFeed()

            val observable = feed.updates

            // transfer feed events to callback
            subscription = observable.subscribe({ update ->
                val event = eventStateStore.get(update.id.uuid)

                if (event == null) {
                    logger.debug("Ignoring ${update.id} state machine update")
                } else {
                    logger.debug("Processing ${update.id} state machine update")
                    if (update is StateMachineUpdate.Removed) {
                        update.result.doOnFailure(Consumer {
                            val os = ByteArrayOutputStream()
                            val pw = PrintWriter(os)
                            pw.println(it.message)
                            it.printStackTrace(pw)
                            pw.close()
                            os.close()
                            event.error = os.toString()

                            // publish with new state
                            val jsonBytes = Serialization.objectMapper().writeValueAsBytes(event)
                            publish("consentRequestErrored", jsonBytes)
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
                { e: Throwable ->
                    // an error occurred in the slave connection, close and reconnect
                    logger.error(e.message)
                    logger.info("Removing observables")

                    // cleanup stuff to make sure we don't leak anything
                    stopListeners()
                })
        } catch (e: IllegalStateException) {
            logger.error("Unexpected exception when starting listener: ${e.message}")
        }
    }

    private fun stopListeners() {
        subscription?.unsubscribe()
    }

    @PreDestroy
    fun destroy() {
        stopListeners()

        masterSlaveConnection.terminate()
    }

    /**
     * Publishes the given data to the given channel
     */
    private fun publish(subject: String, data: ByteArray) {
        natsManagedConnection.getConnection().publish(subject, data)
    }
}
