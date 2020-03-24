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
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.events.infrastructure.ClientException
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.EventName
import nl.nuts.consent.bridge.nats.NATS_CONSENT_REQUEST_SUBJECT
import nl.nuts.consent.state.ConsentBranch
import nl.nuts.consent.state.ConsentState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

/**
 * Pipeline to act on produced Nuts ConsentStates
 */
@Service
class ConsentBranchChangeToNatsPipeline : CordaStateChangeToNatsPipeline<ConsentBranch>() {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun name(): String {
        return stateClass().simpleName
    }

    override fun stateClass(): Class<ConsentBranch> {
        return ConsentBranch::class.java
    }

    override fun stateProduced(stateAndRef: StateAndRef<ConsentBranch>) {
        logger.debug("Received produced state event from Corda: ${stateAndRef.state.data}")

        val state = stateAndRef.state.data

        val event = cordaService.consentBranchToEvent(state)

        // find corresponding event in Nuts event store, if not found create a new state with state == 'to be accepted'
        // the contents of the new event will be a NewConsentRequestState object as json/base64
        var knownEvent: Event? = null
        try {
            logger.debug("Fetching current event state for: ${state.linearId.id}")
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
        publish(NATS_CONSENT_REQUEST_SUBJECT, jsonBytes)
    }

    override fun stateConsumed(stateAndRef: StateAndRef<ConsentBranch>) {
        logger.debug("Received consentBranch consumed event from Corda: ${stateAndRef.state.data}")
    }
}