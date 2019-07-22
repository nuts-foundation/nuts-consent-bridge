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

package nl.nuts.consent.bridge.nats

import com.nhaarman.mockito_kotlin.mock
import nl.nuts.consent.bridge.listener.CordaStateChangeListener
import nl.nuts.consent.state.ConsentRequestState

class StateChangeSubscriberTest {
    private var mockCordaStateChangeListener: CordaStateChangeListener<ConsentRequestState> = mock()
//    private var stateChangeListenerFactory: CordaStateChangeListenerController = mock {
//        on { createInstance<ConsentRequestState>(ArgumentMatchers.anyLong()) } doReturn mockCordaStateChangeListener
//    }


}