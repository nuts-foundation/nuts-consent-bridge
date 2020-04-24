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

package nl.nuts.consent.bridge.pipelines

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import io.nats.streaming.Subscription
import io.nats.streaming.SubscriptionOptions
import net.corda.core.CordaRuntimeException
import net.corda.core.utilities.seconds
import nl.nuts.consent.bridge.ConsentRegistryProperties
import nl.nuts.consent.bridge.Constants
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.api.NotFoundException
import nl.nuts.consent.bridge.corda.CordaManagedConnection
import nl.nuts.consent.bridge.corda.CordaManagedConnectionFactory
import nl.nuts.consent.bridge.corda.CordaService
import nl.nuts.consent.bridge.StateFileStorageControl
import nl.nuts.consent.bridge.io.MasterSlaveConnection
import nl.nuts.consent.bridge.model.FullConsentRequestState
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.EventStateStore
import nl.nuts.consent.bridge.nats.NATS_CONSENT_ERROR_SUBJECT
import nl.nuts.consent.bridge.nats.NATS_CONSENT_REQUEST_SUBJECT
import nl.nuts.consent.bridge.nats.NATS_CONSENT_RETRY_SUBJECT
import nl.nuts.consent.bridge.nats.NatsManagedConnection
import nl.nuts.consent.bridge.nats.NatsManagedConnectionFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Handles events from Nats, event listener is stopped when Corda connection is closed.
 * Stopping subscriptions is to make sure retries are not wasted on during a connection problem (just 1)
 */
@Service
class NatsToCordaPipeline {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var natsManagedConnectionFactory: NatsManagedConnectionFactory
    lateinit var natsManagedConnection: NatsManagedConnection
    lateinit var publisherConnection: NatsManagedConnection

    @Autowired
    lateinit var cordaManagedConnectionFactory: CordaManagedConnectionFactory
    lateinit var cordaManagedConnection: CordaManagedConnection

    lateinit var masterSlaveConnection: MasterSlaveConnection
    lateinit var cordaService: CordaService

    @Autowired
    lateinit var eventStateStore: EventStateStore

    @Autowired
    lateinit var stateFileStorageControl: StateFileStorageControl

    @Autowired
    lateinit var consentRegistryProperties: ConsentRegistryProperties

    private var subscription: Subscription? = null

    /**
     * Setup the masterSlave connection manager
     */
    @PostConstruct
    fun init() {
        natsManagedConnection = natsManagedConnectionFactory.`object`
        publisherConnection = natsManagedConnectionFactory.`object`
        cordaManagedConnection = cordaManagedConnectionFactory.`object`

        masterSlaveConnection = MasterSlaveConnection(cordaManagedConnection, natsManagedConnection)

        cordaService = CordaService(cordaManagedConnection, consentRegistryProperties)

        cordaManagedConnection.name = "natsRPC"
        publisherConnection.name = "retry-publisher"
        natsManagedConnection.name = "event-listener"
        natsManagedConnection.onConnected = {startListeners()}
        natsManagedConnection.onDisconnected = {}

        publisherConnection.connect()
        masterSlaveConnection.connect()
    }

    private fun startListeners() {
        // starting point
        var epoch = stateFileStorageControl.readTimestamp(NATS_CONSENT_REQUEST_SUBJECT)
        if (epoch != 0L) {
            epoch -= 10000L // some overlap for parallel processing
        }
        val startingTime = Instant.ofEpochMilli(epoch)

        // getConnection may raise, but only on broken connection, the reconnect logic will then call startListeners again after a connection is made
        subscription = natsManagedConnection.getConnection().subscribe(NATS_CONSENT_REQUEST_SUBJECT, {

            logger.trace("Received event with data ${String(it.data)}")
            var event: Event? = null

            try {
                event = Serialization.objectMapper().readValue(it.data, Event::class.java)
                try {
                    processEvent(event)
                } catch (e: CordaRuntimeException) { // recoverable
                    logger.error(e.message, e)
                    retry(event)
                } catch (e: IllegalStateException) { // recoverable, connection problem
                    logger.error(e.message, e)
                    retry(event)
                } catch (e: Exception) { // broken
                    logger.error(e.message, e)
                    publishError(event, e.message)
                }
            } catch (e: JsonParseException) { // broken
                logger.error(e.message, e)
                publishError(it.data, e.message)
            } catch (e: JsonMappingException) { // broken
                logger.error(e.message, e)
                publishError(it.data, e.message)
            } catch (e: Exception) { // broken
                logger.error(e.message, e)
            }
        }, SubscriptionOptions.Builder()
            .startAtTime(startingTime)
            .ackWait(5.seconds)
            .durableName("${Constants.NAME}Durable")
            .build()
        )

        logger.info("Nats subscription with subject {} added", NATS_CONSENT_REQUEST_SUBJECT)
    }
    /**
     * Some cleanup
     */
    @PreDestroy
    fun destroy() {
        masterSlaveConnection.terminate()
        publisherConnection.terminate()
    }

    private fun publish(subject:String, data: ByteArray) {
        publisherConnection.getConnection().publish(subject, data)
    }

    private fun retry(retryCount: Int, data: ByteArray) {
        logger.debug("Publishing event to retry queue, retryCount: $retryCount")

        val subject = "$NATS_CONSENT_RETRY_SUBJECT-${retryCount}"
        publish(subject, data)
    }

    private fun retry(e: Event) {
        val newEvent= e.copy(retryCount = e.retryCount + 1)
        val bytes= Serialization.objectMapper().writeValueAsBytes(newEvent)
        retry(newEvent.retryCount, bytes)
    }

    private fun publishError(e: Event, message: String?) {
        val newEvent= e.copy(name = EventName.EventErrored, error = message)
        val bytes= Serialization.objectMapper().writeValueAsBytes(newEvent)
        publish(NATS_CONSENT_ERROR_SUBJECT, bytes)
    }

    private fun publishError(erroredBytes: ByteArray, message: String?) {
        val e = Event(
            name = EventName.EventErrored,
            error = "",
            payload = String(erroredBytes),
            UUID = UUID.randomUUID().toString(),
            externalId = "",
            retryCount = 0
        )
        publishError(e, "$message, event bytes in payload")
    }

    /**
     * delegates specific events to specific functions
     */
    fun processEvent(e: Event) {
        // todo null checks -> error condition
        when (e.name) {
            EventName.EventConsentRequestConstructed -> {
                processEventConsentRequestConstructed(e)
            }
            EventName.EventConsentRequestInFlight -> {
                processEventConsentRequestInFlight(e)
            }
            EventName.EventInFinalFlight -> {
                processEventInFinalFlight(e)
            }
            EventName.EventAllSignaturesPresent -> {
                processEventAllSignaturesPresent(e)
            }
            EventName.EventConsentRequestNacked -> {
                logger.debug("Request for cancellation received, NOT YET IMPLEMENTED")
            }
            EventName.EventAttachmentSigned -> {
                processEventAttachmentSigned(e)
            }
            else -> {
            }
        }
        stateFileStorageControl.writeTimestamp(NATS_CONSENT_REQUEST_SUBJECT, System.currentTimeMillis())
    }

    private fun processEventConsentRequestConstructed(e: Event) {
        logger.debug("Processing consentRequest constructed event with data ${Serialization.objectMapper().writeValueAsString(e)}")

        val payload = Base64.getDecoder().decode(e.payload)
        val consentRequestState = Serialization.objectMapper().readValue(payload, FullConsentRequestState::class.java)

        logger.debug("Checking existing ConsentBranch for UUID: ${consentRequestState.consentId.UUID}")
        if (cordaService.consentBranchExists(consentRequestState.consentId.UUID)) {
            logger.warn("ConsentBranch exists for UUID: ${consentRequestState.consentId.UUID}, possible duplicate event, ignoring")
            return
        }

        val handle = cordaService.createConsentBranch(consentRequestState)

        e.name = EventName.EventConsentRequestInFlight
        e.transactionId = handle.id.uuid.toString()

        eventStateStore.put(handle.id.uuid, e)
        publish(NATS_CONSENT_REQUEST_SUBJECT, Serialization.objectMapper().writeValueAsBytes(e))
    }

    private fun processEventInStateStore(e: Event) {
        logger.debug("Processing ${e.name} event with data ${Serialization.objectMapper().writeValueAsString(e)}")
        // when doing replay
        val uuid = UUID.fromString(e.transactionId)
        val existingEvent = eventStateStore.get(uuid)
        if (existingEvent == null) {
            eventStateStore.put(uuid, e)
        }

        logger.debug("Starting to listen for transaction update for id: ${e.transactionId}")
    }

    private fun processEventConsentRequestInFlight(e: Event) {
        processEventInStateStore(e)
    }

    private fun processEventInFinalFlight(e: Event) {
        processEventInStateStore(e)
    }

    private fun processEventAllSignaturesPresent(e: Event) {
        if (e.initiatorLegalEntity != null) {
            logger.debug("Processing consentRequest all signatures present event with data ${Serialization.objectMapper().writeValueAsString(e)}")

            val cId = e.consentId ?: throw IllegalStateException("missing consentId in event: ${e.UUID}")
            // this node is the initiator, finalize flow
            val handle = cordaService.mergeConsentBranch(cId)

            val eCopy = e.copy(name = EventName.EventInFinalFlight, transactionId = handle.id.uuid.toString())

            eventStateStore.put(handle.id.uuid, eCopy)

            // todo publish as inFlight
        }
    }

    /*
     * Duplicate signatures will cause the flow to fail. So if an earlier event is received, a possible "distributed ConsentRequest received" must be published.
     */
    private fun processEventAttachmentSigned(e: Event) {
        logger.debug("Processing attachment signed event with data ${Serialization.objectMapper().writeValueAsString(e)}")
        val payload = Base64.getDecoder().decode(e.payload)
        val attachmentSignature = Serialization.objectMapper().readValue(payload, PartyAttachmentSignature::class.java)
        val cId = e.consentId ?: throw IllegalStateException("missing consentId in event: ${e.UUID}")

        // find current ConsentBranch
        try {
            val consentBranch = cordaService.consentBranchByUUID(cId)
            if (!consentBranch.signatures.none { it.legalEntityURI == attachmentSignature.legalEntity }) {
                logger.warn("Signature for ${attachmentSignature.legalEntity} in ConsentBranch: $cId already present, possible duplicate event")
                // todo should we republish?
                return
            }
        } catch (e: NotFoundException) {
            // given branch has probably be completed, errored or cancelled already
            // this should have been published by the CordaStateChangeListener
            logger.warn("ConsentBranch missing for UUID: $cId, possible duplicate event, ignoring")
            return
        }

        val handle = cordaService.signConsentBranch(cId, attachmentSignature)

        e.name = EventName.EventConsentRequestInFlight
        e.transactionId = handle.id.uuid.toString()

        eventStateStore.put(handle.id.uuid, e)
        publish(NATS_CONSENT_REQUEST_SUBJECT, Serialization.objectMapper().writeValueAsBytes(e))
    }
}