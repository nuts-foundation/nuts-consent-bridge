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

import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import io.nats.streaming.Subscription
import io.nats.streaming.SubscriptionOptions
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.api.ConsentApiService
import nl.nuts.consent.bridge.model.NewConsentRequestState
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
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

    lateinit var cf: StreamingConnectionFactory
    lateinit var connection: StreamingConnection
    lateinit var subscription: Subscription

    @Autowired
    lateinit var consentService : ConsentApiService

    @PostConstruct
    fun init() {
        logger.debug("Connecting listener to Nats on ${consentBridgeNatsProperties.address} with ClusterID: ${consentBridgeNatsProperties.cluster}")

        cf = StreamingConnectionFactory(consentBridgeNatsProperties.cluster, "cordaBridgePublisher")
        cf.natsUrl = consentBridgeNatsProperties.address
        connection = cf.createConnection()

        subscription = connection.subscribe("consentRequest", {
            try {
                logger.debug("Received event with data ${String(it.data)}")
                val e = Serialization.objectMapper().readValue(it.data, Event::class.java)
                processEvent(e)
            } catch (e : Exception) {
                logger.error("Error during event processing: $e")
            }
        }, SubscriptionOptions.Builder().build())

        logger.info("NutsEventListener connection to Nats server established")
    }

    @PreDestroy
    fun destroy() {
        logger.debug("Disconnecting listener from Nats")

        subscription.unsubscribe()
        connection.close()

        logger.info("Disconnected listener from Nats")
    }

    private fun processEvent(e : Event) {
        // check if state is still correct

        // process if state == "requested" || "accepted"
        if (e.state == "requested") {
            // check if exists

            // if not publish to Corda
            val payload = Base64.getDecoder().decode(e.payload)
            val newConsentRequestState = Serialization.objectMapper().readValue(payload, NewConsentRequestState::class.java)
            consentService.newConsentRequestState(newConsentRequestState)
        } else if (e.state == "accepted") {
            // check if exists

            val payload = Base64.getDecoder().decode(e.payload)
            val partyAttachmentSignature = Serialization.objectMapper().readValue(payload, PartyAttachmentSignature::class.java)
            consentService.acceptConsentRequestState(e.consentId!!, partyAttachmentSignature)
        }
    }
}