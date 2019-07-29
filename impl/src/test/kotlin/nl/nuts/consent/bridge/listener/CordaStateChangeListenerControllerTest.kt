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

package nl.nuts.consent.bridge.rpc.nl.nuts.consent.bridge.listener

import com.nhaarman.mockito_kotlin.mock
import nl.nuts.consent.bridge.events.apis.EventApi
import nl.nuts.consent.bridge.listener.CordaStateChangeListenerController
import nl.nuts.consent.bridge.rpc.CordaService
import org.junit.Before
import org.junit.Test

class CordaStateChangeListenerControllerTest {

    lateinit var cordaStateChangeListenerController: CordaStateChangeListenerController

    val cordaService: CordaService = mock()
    val eventApi: EventApi = mock()

    @Before
    fun setup() {
        cordaStateChangeListenerController = CordaStateChangeListenerController()
    }

    @Test
    fun `publishStateEvent publishes new encountered event`() {

    }
}