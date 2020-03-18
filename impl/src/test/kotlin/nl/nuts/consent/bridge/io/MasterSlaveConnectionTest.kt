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

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class MasterSlaveConnectionTest {

    lateinit var masterSlaveConnection: MasterSlaveConnection
    lateinit var masterConnection: InspectableConnection
    lateinit var slaveConnection: InspectableConnection

    @Before
    fun setup() {
        masterConnection = InspectableConnection()
        slaveConnection = InspectableConnection()
        masterSlaveConnection = MasterSlaveConnection(masterConnection, slaveConnection)
    }

    @Test
    fun `disconnected on master triggers disconnect on slave`() {
        masterConnection.onDisconnected()

        assertEquals("disconnect", slaveConnection.action)
    }

    @Test
    fun `connected on master triggers connect on slave`() {
        masterConnection.onConnected()

        assertEquals("connect", slaveConnection.action)
    }

    @Test
    fun `connect on masterSlave triggers connect on master`() {
        masterConnection.connect()

        assertEquals("connect", masterConnection.action)
    }

    @Test
    fun `disconnect on masterSlave triggers disconnect on master`() {
        masterConnection.disconnect()

        assertEquals("disconnect", masterConnection.action)
    }
}

class InspectableConnection  : EventedConnection<Boolean>() {
    var action: String = "unknown"

    override fun disconnect() { action = "disconnect"; onDisconnected() }
    override fun connect() { action = "connect"; onConnected() }
    override fun terminate() { action = "terminate" }
}