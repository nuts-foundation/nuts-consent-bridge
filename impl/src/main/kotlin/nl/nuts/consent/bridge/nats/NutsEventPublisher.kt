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

/**
 * wrapper class for Nats event publishing, handles connection
 */
@Service
class NutsEventPublisher : NutsEventBase() {
    /**
     * Publishes the given data to the given channel
     *
     * @param subject Nats subject/channel
     * @param data bytes to publish
     */
    // todo fatal errors
    fun publish(subject:String, data: ByteArray) {
        if (connected()) {
            connection?.publish(subject, data)
        } else {
            throw IllegalStateException("Nats server not connected")
        }
    }

    override fun initListener() {
        // noop
    }
}