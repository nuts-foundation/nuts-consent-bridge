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

import net.corda.core.contracts.StateAndRef
import nl.nuts.consent.bridge.EventStoreProperties
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.events.apis.EventApi
import nl.nuts.consent.bridge.events.infrastructure.ClientException
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.NATS_CONSENT_REQUEST_SUBJECT
import nl.nuts.consent.state.ConsentState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.*
import javax.annotation.PostConstruct

/**
 * Pipeline to act on produced Nuts ConsentStates
 */
@Service
class ConsentStateChangeToNatsPipeline : CordaStateChangeToNatsPipeline<ConsentState>() {
    @Autowired
    lateinit var eventstoreProperties: EventStoreProperties
    lateinit var eventApi: EventApi

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Initialize the event API client
     */
    @PostConstruct
    fun initApi() {
        eventApi = EventApi(eventstoreProperties.url)
    }

    override fun name(): String {
        return stateClass().simpleName
    }

    override fun stateClass(): Class<ConsentState> {
        return ConsentState::class.java
    }

    override fun stateProduced(stateAndRef: StateAndRef<ConsentState>) {
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

        if (knownEvent != null) {
            event.UUID = knownEvent.UUID
        } else {
            logger.error("Can't find event in event store with UUID: $consentBranchUUID, generating new UUID")
        }

        event.name = EventName.EventConsentDistributed

        val jsonBytes = Serialization.objectMapper().writeValueAsBytes(event)
        publish(NATS_CONSENT_REQUEST_SUBJECT, jsonBytes)
    }

    override fun stateConsumed(stateAndRef: StateAndRef<ConsentState>) {
        logger.debug("Received consentState consumed event from Corda: ${stateAndRef.state.data}")
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