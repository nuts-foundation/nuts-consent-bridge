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

import com.nhaarman.mockito_kotlin.*
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.TransactionState
import net.corda.core.crypto.SecureHash
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import nl.nuts.consent.bridge.model.EventStreamSetting
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.zmq.Publisher
import nl.nuts.consent.bridge.zmq.Subscription
import nl.nuts.consent.state.ConsentRequestState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner
import org.springframework.test.util.ReflectionTestUtils
import java.util.*
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RunWith(SpringJUnit4ClassRunner::class)
class ConsentApiIntegrationTest {
    @Autowired
    lateinit var testRestTemplate: TestRestTemplate

    @Autowired
    lateinit var consentApiService: ConsentApiService

    private var publisher: Publisher = mock()

    private var cordaRPCOps: CordaRPCOps = mock()
    private var cordaRPClientWrapper:CordaRPClientWrapper = mock {
        on {proxy()} doReturn cordaRPCOps
    }

    @Before
    fun setup() {
        ReflectionTestUtils.setField(consentApiService, "publisher", publisher)
        ReflectionTestUtils.setField(consentApiService, "cordaRPClientWrapper", cordaRPClientWrapper)
    }

    @Test
    fun `POST to api consent event_stream adds subscription to publisher`() {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.accept = listOf(MediaType.TEXT_PLAIN)
        val entity = HttpEntity(EventStreamSetting("topic", 1), headers)
        val resp = testRestTemplate.postForEntity("/api/consent/event_stream", entity, String::class.java)
        assertEquals(HttpStatus.OK, resp.statusCode) // part of protocol

        verify(publisher).addSubscription(Subscription("topic", 1))
    }

    @Test
    fun `GET from api consent_request_state {uuid} returns state`() {
        val mockReference : StateAndRef<ConsentRequestState> = mock()
        val mockState : TransactionState<ConsentRequestState> = mock()

        whenever(mockReference.state).thenReturn(mockState)
        whenever(cordaRPCOps.vaultQueryBy<ConsentRequestState>(criteria = any<QueryCriteria.LinearStateQueryCriteria>(), contractStateType = anyOrNull(), paging = anyOrNull(), sorting = anyOrNull()))
                .thenReturn(Vault.Page(listOf(mockReference), emptyList(), 0L, Vault.StateStatus.ALL, emptyList()))
        whenever(mockState.data).thenReturn(ConsentRequestState("uuid", setOf(SecureHash.allOnesHash), emptyList(), emptyList()))

        val resp = testRestTemplate.getForEntity("/api/consent_request_state/${UUID.randomUUID()}", String::class.java)
        assertEquals(HttpStatus.OK, resp.statusCode)

    }
}