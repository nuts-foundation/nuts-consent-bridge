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
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import java.lang.IllegalStateException
import javax.servlet.http.HttpServletResponse

@ControllerAdvice
class CustomExceptionHandling {

    @ExceptionHandler(value = [IllegalArgumentException::class])
    fun onIllegalArgument(ex: IllegalArgumentException, response: HttpServletResponse): Unit =
            response.sendError(HttpStatus.BAD_REQUEST.value(), ex.message)

    @ExceptionHandler(value = [IllegalStateException::class])
    fun onIllegalState(ex: IllegalStateException, response: HttpServletResponse): Unit =
            response.sendError(HttpStatus.BAD_REQUEST.value(), ex.message)
}