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

import com.nhaarman.mockito_kotlin.mock
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import nl.nuts.consent.bridge.rpc.CordaService
import nl.nuts.consent.model.ConsentMetadata
import nl.nuts.consent.model.Domain
import nl.nuts.consent.model.Period
import nl.nuts.consent.model.SymmetricKey
import nl.nuts.consent.state.ConsentBranch
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.fail

@ActiveProfiles("api")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RunWith(SpringJUnit4ClassRunner::class)
class ConsentApiTest {
    @Autowired
    lateinit var consentApiService: ConsentApiService

    private var cordaService: CordaService = mock()

    @Before
    fun setup() {
        ReflectionTestUtils.setField(consentApiService, "cordaService", cordaService)
    }

    @Test
    fun `consentRequestStateByUUID returns state`() {
        `when`(cordaService.consentBranchByUUID("uuid")).thenReturn(ConsentBranch(UniqueIdentifier(externalId = "1"), UniqueIdentifier(), emptySet(), emptySet(), emptyList(), emptySet()))

        val state = consentApiService.getConsentRequestStateById("uuid")

        assertEquals("1", state.consentId.externalId)
    }

    @Test
    fun `consentRequestStateByUUID raises NotFoundException for not found`() {
        `when`(cordaService.consentBranchByUUID("uuid")).thenThrow(NotFoundException("not found"))

        try {
            consentApiService.getConsentRequestStateById("uuid")
            fail("Expected exception")
        } catch(e : NotFoundException) {

        }
    }

    @Test
    fun `consentRequestStateByUUID raises IllegalStateException for too many results`() {
        `when`(cordaService.consentBranchByUUID("uuid")).thenThrow(IllegalStateException("too many states"))

        try {
            consentApiService.getConsentRequestStateById("uuid")
            fail("Expected exception")
        } catch(e : IllegalStateException) {

        }
    }

    @Test
    fun `getAttachment raises NotFoundException for not found`() {
        `when`(cordaService.getCipherText(SecureHash.parse("afafafafafafafafafafafafafafafafafafafafafafafafafafafafafafafaf"))).thenReturn(null)

        try {
            consentApiService.getAttachmentBySecureHash("afafafafafafafafafafafafafafafafafafafafafafafafafafafafafafafaf")
            fail("exception expected")
        } catch(e: NotFoundException) {

        }
    }

    @Test
    fun `getAttachment returns attachment when exists`() {
        `when`(cordaService.getAttachment(SecureHash.parse("afafafafafafafafafafafafafafafafafafafafafafafafafafafafafafafaf"))).thenReturn(ByteArray(1))

        val att = consentApiService.getAttachmentBySecureHash("afafafafafafafafafafafafafafafafafafafafafafafafafafafafafafafaf")

        assertEquals(1, att.size)
    }

    private fun consentMetadata() : ConsentMetadata {
        return ConsentMetadata(
                domain = listOf(Domain.medical),
                secureKey = SymmetricKey(
                        alg = "alg",
                        iv = "iv"
                ),
                organisationSecureKeys = emptyList(),
                period = Period(
                        validFrom = OffsetDateTime.now()
                ),
                consentRecordHash = "hash"
        )
    }
}