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
import nl.nuts.consent.bridge.events.infrastructure.ClientException
import nl.nuts.consent.bridge.events.infrastructure.ServerException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import java.io.IOException
import java.lang.Exception
import java.lang.IllegalStateException
import javax.servlet.http.HttpServletResponse

/**
 * Mappings from exceptions to API http return codes
 */
@ControllerAdvice
class CustomExceptionHandling {

    val logger = LoggerFactory.getLogger(this.javaClass)

    private fun serverErrorMessage(ex:Exception) : String {
        return "Server error occurred: ${ex.message}"
    }

    /**
     * IllegalArgumentExceptions map to 400 codes
     */
    @ExceptionHandler(value = [IllegalArgumentException::class])
    fun onIllegalArgument(ex: IllegalArgumentException, response: HttpServletResponse): Unit =
            response.sendError(HttpStatus.BAD_REQUEST.value(), ex.message)

    /**
     * IllegalStateExceptions map to 400 codes
     */
    @ExceptionHandler(value = [IllegalStateException::class])
    fun onIllegalState(ex: IllegalStateException, response: HttpServletResponse): Unit =
            response.sendError(HttpStatus.BAD_REQUEST.value(), ex.message)

    /**
     * Event store ClientExceptions map to 500 codes
     */
    @ExceptionHandler(value = [ClientException::class])
    fun onApiClientException(ex: ClientException, response: HttpServletResponse) {
        logger.error(serverErrorMessage(ex))
        return response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value(), ex.message)
    }

    /**
     * Event store ServerExceptions map to 500 codes
     */
    @ExceptionHandler(value = [ServerException::class])
    fun onApiServerException(ex: ServerException, response: HttpServletResponse) {
        logger.error(serverErrorMessage(ex))
        return response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value(), ex.message)
    }

    /**
     * Registry ClientExceptions map to 500 codes
     */
    @ExceptionHandler(value = [nl.nuts.consent.bridge.registry.infrastructure.ClientException::class])
    fun onApiRegistryClientException(ex: ClientException, response: HttpServletResponse) {
        logger.error(serverErrorMessage(ex))
        return response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value(), ex.message)
    }

    /**
     * Registry ServerExceptions map to 500 codes
     */
    @ExceptionHandler(value = [nl.nuts.consent.bridge.registry.infrastructure.ServerException::class])
    fun onApiRegisitryServerException(ex: ServerException, response: HttpServletResponse) {
        logger.error(serverErrorMessage(ex))
        return response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value(), ex.message)
    }

    /**
     * IOExceptions map to 500 codes
     */
    @ExceptionHandler(value = [IOException::class])
    fun onIOException(ex: IOException, response: HttpServletResponse) {
        logger.error("Server error occurred: ${ex.message}", ex)
        return response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value(), ex.message)
    }

    /**
     * Json mapping exceptions are mapped to 400 codes
     */
    @ExceptionHandler(value = [JsonMappingException::class])
    fun onJsonMappingException(ex: JsonMappingException, response: HttpServletResponse) {
        logger.error("JsonMapping exception occurred: ${ex.message}", ex)
        return response.sendError(HttpStatus.BAD_REQUEST.value(), ex.message)
    }
}