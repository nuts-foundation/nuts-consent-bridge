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

import com.fasterxml.jackson.module.kotlin.readValue
import nl.nuts.consent.bridge.Serialization
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventTest {
    @Test
    fun testEnumUnmarshall() {
        val json = "{\"name\":\"${EventName.EventConsentDistributed.value}\", \"uuid\":\"\",\"externalId\":\"\",\"payload\":\"\"}"

        val e:Event = Serialization.objectMapper().readValue(json)

        assertEquals(EventName.EventConsentDistributed, e.name)
    }

    @Test
    fun testEnumMarshall() {
        val event = Event(
                UUID = "",
                externalId = "",
                payload = "",
                retryCount = 0,
                name = EventName.EventConsentDistributed
        )
        val json = Serialization.objectMapper().writeValueAsString(event)

        assertTrue(json.contains(EventName.EventConsentDistributed.value))
    }
}