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

package nl.nuts.consent.bridge.zmq

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import nl.nuts.consent.bridge.ConsentBridgeZMQProperties
import nl.nuts.consent.bridge.listener.StateChangeListener
import nl.nuts.consent.bridge.listener.StateChangeListenerFactory
import nl.nuts.consent.state.ConsentRequestState
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ

class PublisherTest {

    lateinit var publisher: Publisher

    private var mockStateChangeListener: StateChangeListener<ConsentRequestState> = mock()
    private var stateChangeListenerFactory: StateChangeListenerFactory = mock {
        on { createInstance<ConsentRequestState>(ArgumentMatchers.anyLong()) } doReturn mockStateChangeListener
    }

    private var socket: ZMQ.Socket = mock()
    private var context: ZContext = mock {
        on { createSocket(SocketType.PUB) } doReturn socket
    }

    @Before
    fun setup() {
        publisher = Publisher()
        publisher.stateChangeListenerFactory = stateChangeListenerFactory
        publisher.context = context
        publisher.consentBridgeZMQProperties = ConsentBridgeZMQProperties()
    }

    @Test
    fun `addSubscription creates stateChangeListener and starts listening to socket`() {
        //when
        publisher.addSubscription(Subscription("topic", 0))

        // then
        verify(mockStateChangeListener).start(ConsentRequestState::class.java)
        verify(socket).connect("inproc://proxy")
    }

    @Test
    fun `removeSubscription stops stateChangeListener and closes socket`() {
        //when
        val subscription = Subscription("topic", 0)
        publisher.addSubscription(subscription)
        publisher.removeSubscription(subscription)

        //then
        verify(mockStateChangeListener).terminate()
        verify(socket).send(ZMQ.PROXY_TERMINATE)
    }

    @Test
    fun `destroy removes all subscriptions`() {
        //when
        publisher.addSubscription(Subscription("topic", 0))
        publisher.destroy()

        //then
        verify(mockStateChangeListener).terminate()
        verify(socket).send(ZMQ.PROXY_TERMINATE)
    }

}