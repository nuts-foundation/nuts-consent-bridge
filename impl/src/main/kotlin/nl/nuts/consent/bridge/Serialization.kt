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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import java.text.SimpleDateFormat

class Serialization {
    companion object {
        val _objectMapper: ObjectMapper by lazy {
            val objectMapper = ObjectMapper()
            objectMapper.registerModule(JavaTimeModule())
            objectMapper.dateFormat = SimpleDateFormat.getDateInstance()
            objectMapper
        }

        fun objectMapper(): ObjectMapper {
            return _objectMapper
        }
    }
}