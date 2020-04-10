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

package nl.nuts.consent.bridge

import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.model.SignatureWithKey
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails


class SerializationTest {

    @Test
    fun `roundtrip PartyAttachmentSignature with JWS`() {
        val expected = PartyAttachmentSignature("foobar", "1234", "JWS")
        val actual = Serialization.objectMapper().readValue(Serialization.objectMapper().writeValueAsString(expected), PartyAttachmentSignature::class.java)
        assertEquals(expected.legalEntity, actual.legalEntity)
        assertEquals(expected.attachment, actual.attachment)
        assertEquals(expected.signature, actual.signature)
    }

    @Test
    fun `roundtrip PartyAttachmentSignature with SignatureWithKey`() {
        val expected = PartyAttachmentSignature("foobar", "1234", SignatureWithKey(mapOf("foobar" to "bar"), "sigsigsig"))
        val actual = Serialization.objectMapper().readValue(Serialization.objectMapper().writeValueAsString(expected), PartyAttachmentSignature::class.java)
        assertEquals(expected.legalEntity, actual.legalEntity)
        assertEquals(expected.attachment, actual.attachment)
        assertEquals(expected.signature, actual.signature)
    }

    @Test
    fun `PartyAttachmentSignature with unsupported signature format`() {
        val input = PartyAttachmentSignature("foobar", "1234", listOf("foo", "bar"))
        assertFails("Either JWS as string or SignatureWithKey as object was expected") {
            Serialization.objectMapper().readValue(Serialization.objectMapper().writeValueAsString(input), PartyAttachmentSignature::class.java)
        }
    }
}