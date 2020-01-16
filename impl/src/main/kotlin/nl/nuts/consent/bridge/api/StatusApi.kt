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

package nl.nuts.consent.bridge.api

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.HealthIndicatorRegistry
import org.springframework.boot.actuate.health.OrderedHealthAggregator
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod

/**
 * Simple REST service for determining if the bridge is running.
 */
@Controller
@RequestMapping("\${api.base-path:}/status")
class StatusApi {
    @Autowired
    lateinit var healthAggregator: OrderedHealthAggregator

    @Autowired
    lateinit var healthIndicatorRegistry: HealthIndicatorRegistry

    /**
     * Http service that always returns a 200 OK with an "OK" body.
     * Can be used for checking if the bridge is up and running.
     */
    @RequestMapping(
            value = ["/"],
            produces = ["text/plain"],
            method = [RequestMethod.GET])
    fun getStatus() : ResponseEntity<String> {
        // NOP == OK
        return ResponseEntity("OK", HttpStatus.OK)
    }

    /**
     * Http service that always returns a 200 OK with an overview of component health.
     * Can be used for debugging and manual checks.
     */
    @RequestMapping(
            value = ["/diagnostics"],
            produces = ["text/plain"],
            method = [RequestMethod.GET])
    fun diagnostics() : ResponseEntity<String> {
        val h = healthAggregator.aggregate(healthIndicatorRegistry.all.map { it.key to it.value.health() }.toMap())

        var checks = h.details.map(Map.Entry<String, Any>::toString).toSet().joinToString("\n")

        return ResponseEntity("General status=${h.status.code}\n${checks}", HttpStatus.OK)
    }
}