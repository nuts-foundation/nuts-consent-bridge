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

package nl.nuts.consent.bridge.diagnostics

import nl.nuts.consent.bridge.nats.NatsManagedConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy


/**
 * Simple check to know if a Nats Connection can be established
 */
@Component
class NatsHealthIndicator : HealthIndicator {

    @Autowired
    lateinit var natsManagedConnection: NatsManagedConnection

    /**
     * Init Nats managed connection
     */
    @PostConstruct
    fun init() {
        natsManagedConnection.name = "health"
        natsManagedConnection.connect()
    }

    /**
     * Destroy Nats managed connection
     */
    @PreDestroy
    fun destroy() {
        natsManagedConnection?.terminate()
    }

    override fun health(): Health {
        try {
            natsManagedConnection.getConnection() // throws exc
            return Health.up().build()
        } catch (e: IllegalStateException) {
            return Health.down(e).build()
        }
    }
}