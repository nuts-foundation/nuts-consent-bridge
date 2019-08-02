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

package nl.nuts.consent.bridge.nats

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import io.nats.streaming.Subscription
import io.nats.streaming.SubscriptionOptions
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.model.FullConsentRequestState
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.rpc.CordaService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.IOException
import java.lang.IllegalStateException
import java.util.*
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy


/**
 * Control class for linking CordaStateChangeListener to publisher topics.
 */
@Service
class NutsEventListener {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var consentBridgeNatsProperties: ConsentBridgeNatsProperties

    @Autowired
    lateinit var cordaService : CordaService

    @Autowired
    lateinit var eventStateStore: EventStateStore

    lateinit var cf: StreamingConnectionFactory
    lateinit var connection: StreamingConnection
    lateinit var subscription: Subscription

    @Autowired
    lateinit var nutsEventPublisher: NutsEventPublisher

    /**
     * Initializes the connection to the Nats streaming server and creates a subscription to channel with subject: "consentRequest"
     *
     * It uses the standard SubscriptionOptions. Server config is loaded via Spring properties: nuts.consent.nats.*.
     *
     * The subscription receives all events but only processes: ["consentRequest constructed", "consentRequest in flight", "consentRequest in flight for final state", "all signatures present", "attachment signed"]
     */
    @PostConstruct
    fun init() {
        logger.debug("Connecting listener to Nats on ${consentBridgeNatsProperties.address} with ClusterID: ${consentBridgeNatsProperties.cluster}")

        cf = StreamingConnectionFactory(consentBridgeNatsProperties.cluster, "cordaBridgePublisher-${Integer.toHexString(Random().nextInt())}")
        cf.natsUrl = consentBridgeNatsProperties.address
        connection = cf.createConnection()

        subscription = connection.subscribe(NATS_CONSENT_REQUEST_SUBJECT, {
            try {
                logger.debug("Received event with data ${String(it.data)}")
                val e = Serialization.objectMapper().readValue(it.data, Event::class.java)
                processEvent(e)
            } catch (e : IOException) {
                logger.error("Error during event processing: $e")
            } catch (e : JsonParseException) {
                logger.error("Error during event processing: $e")
            } catch (e : JsonMappingException) {
                logger.error("Error during event processing: $e")
            }
            // todo: more exceptions?
        }, SubscriptionOptions.Builder().build())

        logger.info("NutsEventListener connection to Nats server established")
    }

    /**
     * stop subscription and close Nats connection
     */
    @PreDestroy
    fun destroy() {
        logger.debug("Disconnecting listener from Nats")

        subscription.unsubscribe()
        connection.close()

        logger.info("Disconnected listener from Nats")
    }

    private fun processEvent(e : Event) {
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
            } else -> {}
        }
    }

    private fun processEventConsentRequestConstructed(e:Event) {
        val payload = Base64.getDecoder().decode(e.payload)
        val consentRequestState = Serialization.objectMapper().readValue(payload, FullConsentRequestState::class.java)
        val handle = cordaService.newConsentRequestState(consentRequestState)

        e.name = EventName.EventConsentRequestInFlight
        e.transactionId = handle.id.uuid.toString()

        eventStateStore.put(handle.id.uuid, e)
        nutsEventPublisher.publish(NATS_CONSENT_REQUEST_SUBJECT, Serialization.objectMapper().writeValueAsBytes(e))
    }

    private fun processEventConsentRequestInFlight(e:Event) {
        // when doing replay
        val uuid = UUID.fromString(e.transactionId)
        val existingEvent = eventStateStore.get(uuid)
        if (existingEvent == null) {
            eventStateStore.put(uuid, e)
        }

        logger.debug("Starting to listen for transaction update for id: ${e.transactionId}")
    }

    private fun processEventInFinalFlight(e:Event) {
        // when doing replay
        val uuid = UUID.fromString(e.transactionId)
        val existingEvent = eventStateStore.get(uuid)
        if (existingEvent == null) {
            eventStateStore.put(uuid, e)
        }

        logger.debug("Starting to listen for transaction update for id: ${e.transactionId}")
    }

    private fun processEventAllSignaturesPresent(e: Event) {
        if (e.initiatorLegalEntity != null) {
            val cId = e.consentId ?: throw IllegalStateException("missing consentId in event: ${e.UUID}")
            // this node is the initiator, finalize flow
            val handle = cordaService.finalizeConsentRequestState(cId)

            e.name = EventName.EventInFinalFlight
            e.transactionId = handle.id.uuid.toString()
            eventStateStore.put(handle.id.uuid, e)
        }
    }

    private fun processEventAttachmentSigned(e: Event) {
        val payload = Base64.getDecoder().decode(e.payload)
        val attachmentSignature = Serialization.objectMapper().readValue(payload, PartyAttachmentSignature::class.java)
        val cId = e.consentId ?: throw IllegalStateException("missing consentId in event: ${e.UUID}")
        val handle = cordaService.acceptConsentRequestState(cId, attachmentSignature)

        e.name = EventName.EventConsentRequestInFlight
        e.transactionId = handle.id.uuid.toString()

        eventStateStore.put(handle.id.uuid, e)
        nutsEventPublisher.publish(NATS_CONSENT_REQUEST_SUBJECT, Serialization.objectMapper().writeValueAsBytes(e))
    }
}