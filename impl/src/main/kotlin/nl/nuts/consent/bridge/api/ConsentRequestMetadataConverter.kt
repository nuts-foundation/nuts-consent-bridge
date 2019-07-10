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

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.nuts.consent.bridge.model.ConsentRequestMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import java.lang.IllegalArgumentException

//@Component
class ConsentRequestMetadataConverter : Converter<String, ConsentRequestMetadata> {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    override fun convert(source: String): ConsentRequestMetadata? {
        try {
            return objectMapper.readValue<ConsentRequestMetadata>(source)
        } catch (e: JsonMappingException) {
            throw IllegalArgumentException(e)
        }
    }


}