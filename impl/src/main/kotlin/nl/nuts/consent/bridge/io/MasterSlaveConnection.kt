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
 * Class that connects a master and slave connection together. The connection logic for the slave follows what the master does.
 * The slave is usually an eventListener, since it should back-off if the downstream producer can't deliver events.
 *
 * Any logic for acting on events on the slave and logic for calling the master from these events should be in the
 * same class
 */
class MasterSlaveConnection(var master: EventedConnection<out Any>, var slave: EventedConnection<out Any>) : ManagedConnection {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    init {
        this.master.onDisconnected = {
            logger.trace("master onDisconnected called")
            this.slave.disconnect()
        }
        this.master.onConnected = {
            logger.trace("master onConnected called")
            this.slave.connect()
        }
    }

    override fun disconnect() = this.master.disconnect()
    override fun connect()  = this.master.connect()

    override fun terminate() {
        this.master.terminate()
        this.slave.terminate()
    }
}