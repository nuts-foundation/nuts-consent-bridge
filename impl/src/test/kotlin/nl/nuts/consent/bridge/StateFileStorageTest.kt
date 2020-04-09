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

package nl.nuts.consent.bridge

import nl.nuts.consent.bridge.EventMetaProperties
import nl.nuts.consent.bridge.StateFileStorageControl
import org.junit.Test
import java.io.File
import java.util.*
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateFileStorageTest {

    val baseLocation = "./temp"
    val control = StateFileStorageControl()

    init {
        control.eventMetaProperties = EventMetaProperties(baseLocation)
    }

    @AfterTest
    fun cleanup() {
        File("$baseLocation/test.stamp").delete()
    }

    @Test
    fun `writing timestamp to unknown type creates file`() {
        val epoch = Date().time

        control.writeTimestamp("test", epoch)

        assertTrue(File("$baseLocation/test.stamp").exists())
    }

    @Test
    fun `written timestamp can be read`() {
        val epoch = Date().time

        control.writeTimestamp("test", epoch)

        assertEquals(epoch, control.readTimestamp("test"))
    }

    @Test
    fun `timestamp can be overwritten`() {
        val epoch = Date().time
        val epochPlus10 = epoch + 10

        control.writeTimestamp("test", epoch)
        control.writeTimestamp("test", epochPlus10)

        assertEquals(epochPlus10, control.readTimestamp("test"))
    }

    @Test
    fun `in parallel execution, highest timestamp wins`() {
        val epoch = Date().time
        for (i in 1..10) {
            Thread {
                Thread.sleep(10L + (Math.random() + 5).toLong())
                control.writeTimestamp("test", epoch + i)
            }.run()
        }

        Thread.sleep(25L)

        assertEquals(epoch + 10, control.readTimestamp("test"))
    }
}