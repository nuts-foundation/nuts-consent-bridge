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

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod

/**
 * Simple REST service for determining if the bridge is running.
 */
@Controller
@RequestMapping("\${api.base-path:}")
class StatusApi {
    /**
     * REST service that always returns a 200 OK with an empty body.
     * Can be used for checking if the bridge is up and running.
     */
    @RequestMapping(
            value = ["/status"],
            method = [RequestMethod.GET])
    fun getStatus() : ResponseEntity<Unit> {
        // NOP == OK
        return ResponseEntity(HttpStatus.OK)
    }
}