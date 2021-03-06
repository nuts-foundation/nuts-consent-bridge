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

package nl.nuts.consent.bridge.io

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Base connection wrapper interface
 */
interface ManagedConnection {
    /**
     * Closes connection
     */
    fun disconnect()

    /**
     * Tries to connect with automatic reconnect
     */
    fun connect()

    /**
     * Stops everything, no change on getting a new connection ever again
     */
    fun terminate()
}

/**
 * Managed connection which automatically reconnects with backoff period.
 * Status changes are routed to callbacks.
 */
abstract class EventedConnection<T> : ManagedConnection {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    var onConnected: () -> Unit = {logger.warn("No callback registered for ${this::class.java.simpleName}")}
    var onDisconnected: () -> Unit = {logger.warn("No callback registered for ${this::class.java.simpleName}")}
}
