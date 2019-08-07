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

import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 *
 * Simple service for sharing event/state machine state across listeners/publishers.
 * Uses a concurrent HashMap as underlying store.
 */
@Service
class EventStateStore {
    private val eventSet = ConcurrentHashMap<UUID, Event>()

    /**
     * add a event to the store
     *
     * @param uuid Event unique id
     * @param event
     *
     * @return the event
     */
    fun put(uuid: UUID, event: Event) : Event {
        eventSet[uuid] = event
        return event
    }

    /**
     * retrieve event from store by its uuid
     *
     * @param uuid Event unique id
     *
     * @return stored event or null
     */
    fun get(uuid: UUID) : Event? {
        return eventSet[uuid]
    }

    /**
     * remove event from store by its uuid
     *
     * @param uuid Event unique id
     *
     * @return the event when removed or null when not
     */
    fun remove(uuid: UUID) : Event? {
        return eventSet.remove(uuid)
    }
}