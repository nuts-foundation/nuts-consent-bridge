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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.nhaarman.mockito_kotlin.*
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.testing.core.TestIdentity
import nl.nuts.consent.bridge.model.*
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.bridge.zmq.Publisher
import nl.nuts.consent.bridge.zmq.Subscription
import nl.nuts.consent.contract.AttachmentSignature
import nl.nuts.consent.flow.ConsentRequestFlows
import nl.nuts.consent.state.ConsentRequestState
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.io.FileSystemResource
import org.springframework.http.*
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner
import org.springframework.test.util.ReflectionTestUtils
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.*
import kotlin.test.assertEquals
import org.springframework.util.LinkedMultiValueMap
import org.springframework.http.HttpEntity
import java.io.File
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.time.LocalDate
import kotlin.test.assertTrue


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

    companion object {
        val PORT = 8081
        val wireMockServer: WireMockServer = WireMockServer(wireMockConfig().port(PORT))

        init {
            wireMockServer.start()
            WireMock.configureFor("localhost", PORT)
        }
    }

    @Before
    fun setup() {
        ReflectionTestUtils.setField(consentApiService, "publisher", publisher)
        ReflectionTestUtils.setField(consentApiService, "cordaRPClientWrapper", cordaRPClientWrapper)
    }

    @After
    fun cleanup() {
        WireMock.reset()
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
        whenever(mockState.data).thenReturn(consentRequestState())

        val resp = testRestTemplate.getForEntity("/api/consent_request_state/${UUID.randomUUID()}", String::class.java)
        assertEquals(HttpStatus.OK, resp.statusCode)

    }

    @Test
    fun `GET from api consent_request_state {uuid} returns not found for non existing state`() {
        whenever(cordaRPCOps.vaultQueryBy<ConsentRequestState>(criteria = any<QueryCriteria.LinearStateQueryCriteria>(), contractStateType = anyOrNull(), paging = anyOrNull(), sorting = anyOrNull()))
                .thenReturn(Vault.Page(emptyList(), emptyList(), 0L, Vault.StateStatus.ALL, emptyList()))

        val resp = testRestTemplate.getForEntity("/api/consent_request_state/${UUID.randomUUID()}", String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, resp.statusCode)

    }

    @Test
    fun `GET from api consent_request_state {uuid} returns 400 for too many states`() {
        val mockReference : StateAndRef<ConsentRequestState> = mock()

        whenever(cordaRPCOps.vaultQueryBy<ConsentRequestState>(criteria = any<QueryCriteria.LinearStateQueryCriteria>(), contractStateType = anyOrNull(), paging = anyOrNull(), sorting = anyOrNull()))
                .thenReturn(Vault.Page(listOf(mockReference, mockReference), emptyList(), 0L, Vault.StateStatus.ALL, emptyList()))

        val resp = testRestTemplate.getForEntity("/api/consent_request_state/${UUID.randomUUID()}", String::class.java)
        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
    }

    @Test
    fun `GET from api attachment {secureHash} returns 404 for unknown attachment`() {
        whenever(cordaRPCOps.attachmentExists(anyOrNull())).thenReturn(false)

        val resp = testRestTemplate.getForEntity("/api/attachment/${SecureHash.allOnesHash}", String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, resp.statusCode)
    }

    @Test
    fun `GET from api attachment {secureHash} returns attachment when exists`() {
        val stream = ByteArrayInputStream("hi".toByteArray())

        whenever(cordaRPCOps.attachmentExists(anyOrNull())).thenReturn(true)
        whenever(cordaRPCOps.openAttachment(SecureHash.allOnesHash)).thenReturn(stream)

        val headers = HttpHeaders()
        headers.accept = listOf(MediaType.ALL)
        val req = RequestEntity<ByteArray>(null, headers, HttpMethod.GET, URI("/api/attachment/${SecureHash.allOnesHash}"))
        val resp = testRestTemplate.exchange(req, ByteArray::class.java)

        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("hi", String(resp.body!!))
    }

    @Test
    fun `POST for api consent consent_request with incomplete consentRequestMetadata returns 400`() {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON

        val entity = HttpEntity("{}", headers)
        val resp = testRestTemplate.postForEntity("/api/consent/consent_request", entity, String::class.java)

        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
    }

    @Test
    fun `POST for api consent consent_request with invalid attachment returns 400`() {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON

        val entity = HttpEntity(invalidConsentRequestState(), headers)
        val resp = testRestTemplate.postForEntity("/api/consent/consent_request", entity, String::class.java)

        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertTrue(resp.body!!.contains("given attachment is not using valid base64 encoding"))
    }

    @Test
    fun `POST for api consent consent_request returns 200`() {
        stubFor(get(urlEqualTo("/api/endpoints?orgIds=test&type=https://nuts.nl/CodeSystem/endpoint-type%23consent"))
            .willReturn(aResponse()
                .withBody("[]")))


        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON

        val entity = HttpEntity(newConsentRequestState(), headers)
        val resp = testRestTemplate.postForEntity("/api/consent/consent_request", entity, String::class.java)

        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("OK", resp.body!!)
    }

    @Test
    fun `POST for api consent consent_request returns 500 if registry returns error`() {
        stubFor(get(urlEqualTo("/api/endpoints?orgIds=test&type=https://nuts.nl/CodeSystem/endpoint-type%23consent"))
                .willReturn(aResponse()
                        .withStatus(500)))


        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON

        val entity = HttpEntity(newConsentRequestState(), headers)
        val resp = testRestTemplate.postForEntity("/api/consent/consent_request", entity, String::class.java)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.statusCode)
    }

    @Test
    fun `POST for api consent consent_request {uuid} finalize returns 200`() {
        val uuid = UUID.randomUUID()
        whenever(cordaRPCOps.startFlow(ConsentRequestFlows::FinalizeConsentRequest, UniqueIdentifier("dummy", uuid))).thenReturn(null)

        val resp = testRestTemplate.postForEntity("/api/consent/consent_request/$uuid/finalize", "", String::class.java)

        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("OK", resp.body!!)
    }

    @Test
    fun `POST for api consent consent_request {uuid} accept returns 200`() {
        val uuid = UUID.randomUUID()

        whenever(cordaRPCOps.startFlow(eq(ConsentRequestFlows::AcceptConsentRequest), eq(UniqueIdentifier("dummy", uuid)), listOf(anyOrNull()))).thenReturn(null)

        val resp = testRestTemplate.postForEntity("/api/consent/consent_request/$uuid/accept", partyAttachmentSignature(), String::class.java)

        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("OK", resp.body!!)
    }

    private fun partyAttachmentSignature() : PartyAttachmentSignature {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keypair = generator.genKeyPair()

        val stringWriter = StringWriter()
        val writer = PemWriter(stringWriter)
        writer.writeObject(PemObject("PUBLIC KEY", keypair.public.encoded))
        writer.flush()

        return PartyAttachmentSignature(
                legalEntityURI = "legalEntity",
                attachment = SecureHash.allOnesHash.toString(),
                signature = SignatureWithKey(
                        publicKey = stringWriter.toString(),
                        data = Base64.getEncoder().encodeToString("data".toByteArray())
                )
        )
    }

    private fun newConsentRequestState() : NewConsentRequestState {
        return NewConsentRequestState(
                externalId = "externalId",
                metadata = Metadata(
                        domain = listOf(Domain.medical),
                        organisationSecureKeys = listOf(
                                ASymmetricKey(
                                        legalEntityURI = "test",
                                        alg = "RSA_3K",
                                        cipherText = "encrypted cypher"
                                )
                        ),
                        period = Period(validFrom = LocalDate.now()),
                        secureKey = SymmetricKey(
                                alg = "aes_gcm",
                                iv = "iv"
                        )
                ),
                attachment = base64EncodedValidAttachment())
    }

    private fun invalidConsentRequestState() : NewConsentRequestState {
        return NewConsentRequestState(
                externalId = "externalId",
                metadata = Metadata(
                        domain = listOf(Domain.medical),
                        organisationSecureKeys = listOf(
                                ASymmetricKey(
                                        legalEntityURI = "test",
                                        alg = "RSA_3K",
                                        cipherText = "encrypted cypher"
                                )
                        ),
                        period = Period(validFrom = LocalDate.now()),
                        secureKey = SymmetricKey(
                                alg = "aes_gcm",
                                iv = "iv"
                        )
                ),
                attachment = "A")
    }

    private fun base64EncodedValidAttachment() : String {
        val loc = "src/test/resources/valid_metadata.zip"
        return Base64.getEncoder().encodeToString(File(loc).inputStream().readBytes())
    }

    private fun consentRequestState() : ConsentRequestState {
        val testIdentity = TestIdentity(CordaX500Name.parse("C=NL, L=Groenlo, O=Nuts"))
        val party = testIdentity.party

        return ConsentRequestState(
                externalId = "uuid",
                attachments = setOf(SecureHash.allOnesHash),
                parties = listOf(
                        party
                ),
                signatures = listOf(
                        AttachmentSignature(
                                legalEntityURI = "legalEntity",
                                attachmentHash = SecureHash.allOnesHash,
                                signature = DigitalSignature.WithKey(
                                        by = testIdentity.publicKey,
                                        bytes = ByteArray(16))
                        )
                )
        )
    }
}