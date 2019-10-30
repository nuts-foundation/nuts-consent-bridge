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
import io.nats.client.Connection
import io.nats.client.ConnectionListener
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
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.streaming.*
import nl.nuts.consent.bridge.Constants
import nl.nuts.consent.bridge.api.NotFoundException
import org.bouncycastle.crypto.tls.ConnectionEnd.server
import java.time.Duration


/**
 * Control class for linking CordaStateChangeListener to publisher topics.
 */
@Service
class NutsEventListener : NutsEventBase() {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var cordaService : CordaService

    @Autowired
    lateinit var eventStateStore: EventStateStore

    var subscription: Subscription? = null

    @Autowired
    lateinit var nutsEventPublisher: NutsEventPublisher

    override fun initListener() {
        subscription = connection?.subscribe(NATS_CONSENT_REQUEST_SUBJECT, {
            try {
                logger.trace("Received event with data ${String(it.data)}")
                val e = Serialization.objectMapper().readValue(it.data, Event::class.java)
                processEvent(e)
            } catch (e : IOException) {
                logger.error(e.message, e)
            } catch (e : JsonParseException) {
                logger.error(e.message, e)
            } catch (e : JsonMappingException) {
                logger.error(e.message, e)
            } catch (e : Exception) {
                logger.error(e.message, e)
            }
        }, SubscriptionOptions.Builder()
                .startWithLastReceived()
                .durableName("${Constants.NAME}Durable")
                .build()
        )

        logger.info("Nats subscrtiption with subject {} added", NATS_CONSENT_REQUEST_SUBJECT)
    }

    override fun name() : String {
        return "listener"
    }

    /**
     * stop subscription and close Nats connection
     */
    @PreDestroy
    fun destroy() {
        logger.debug("Closing subscription at Nats (queue remains)")

        subscription?.close()
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
        nutsEventPublisher.publish(NATS_CONSENT_REQUEST_SUBJECT, Serialization.objectMapper().writeValueAsBytes(e))
    }

    private fun processEventConsentRequestInFlight(e:Event) {
        logger.debug("Processing consentRequest in flight event with data ${Serialization.objectMapper().writeValueAsString(e)}")
        // when doing replay
        val uuid = UUID.fromString(e.transactionId)
        val existingEvent = eventStateStore.get(uuid)
        if (existingEvent == null) {
            eventStateStore.put(uuid, e)
        }

        logger.debug("Starting to listen for transaction update for id: ${e.transactionId}")
    }

    private fun processEventInFinalFlight(e:Event) {
        logger.debug("Processing consentRequest in final flight event with data ${Serialization.objectMapper().writeValueAsString(e)}")
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
            logger.debug("Processing consentRequest all signatures present event with data ${Serialization.objectMapper().writeValueAsString(e)}")

            val cId = e.consentId ?: throw IllegalStateException("missing consentId in event: ${e.UUID}")
            // this node is the initiator, finalize flow
            val handle = cordaService.mergeConsentBranch(cId)

            e.name = EventName.EventInFinalFlight
            e.transactionId = handle.id.uuid.toString()
            eventStateStore.put(handle.id.uuid, e)

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
        } catch (e:NotFoundException) {
            // given branch has probably be completed, errored or cancelled already
            // this should have been published by the CordaStateChangeListener
            logger.warn("ConsentBranch missing for UUID: $cId, possible duplicate event, ignoring")
            return
        }

        val handle = cordaService.signConsentBranch(cId, attachmentSignature)

        e.name = EventName.EventConsentRequestInFlight
        e.transactionId = handle.id.uuid.toString()

        eventStateStore.put(handle.id.uuid, e)
        nutsEventPublisher.publish(NATS_CONSENT_REQUEST_SUBJECT, Serialization.objectMapper().writeValueAsBytes(e))
    }
}