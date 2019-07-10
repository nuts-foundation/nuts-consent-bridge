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

package nl.nuts.consent.bridge.conversion

import nl.nuts.consent.bridge.model.ASymmetricKey
import nl.nuts.consent.bridge.model.Domain
import nl.nuts.consent.bridge.model.Period
import nl.nuts.consent.bridge.model.SymmetricKey
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.convert.ConversionService
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RunWith(SpringJUnit4ClassRunner::class)
class ConversionTest {
    @Qualifier("mvcConversionService")
    @Autowired
    lateinit var conversionService: ConversionService

    val testPeriod = Period(validFrom = OffsetDateTime.now(), validTo = OffsetDateTime.now())
    val testDomain = Domain.medical
    val testSymmetricKey = SymmetricKey(alg = "AES_GCM", iv = "iv")
    val testAsymmetricKey = ASymmetricKey(legalEntity = "legal", alg = "alg", cipherText = "cipher")
    val testMetadata = nl.nuts.consent.bridge.model.Metadata(
            domain = listOf(testDomain),
            secureKey = testSymmetricKey,
            organisationSecureKeys = listOf(testAsymmetricKey),
            period = testPeriod
    )

    @Test
    fun `Period is converted correctly`() {
        val cPeriod = conversionService.convert(testPeriod, nl.nuts.consent.model.Period::class.java)!!
        assertEquals(LocalDate.now(), cPeriod.validFrom)
        assertEquals(LocalDate.now(), cPeriod.validTo)
    }

    @Test
    fun `Period without validTo is converted correctly`() {
        val testPeriod = Period(validFrom = OffsetDateTime.now())
        val cPeriod = conversionService.convert(testPeriod, nl.nuts.consent.model.Period::class.java)!!
        assertEquals(LocalDate.now(), cPeriod.validFrom)
        assertNull(cPeriod.validTo)
    }

    @Test
    fun `Domain is converted correctly`() {
        val cDomain = conversionService.convert(testDomain, nl.nuts.consent.model.Domain::class.java)!!
        assertEquals("medical", cDomain.name)
    }

    @Test
    fun `Symmetrickey is converted correctly`() {
        val csk = conversionService.convert(testSymmetricKey, nl.nuts.consent.model.SymmetricKey::class.java)!!
        assertEquals(testSymmetricKey.iv, csk.iv)
        assertEquals(testSymmetricKey.alg, csk.alg)

    }

    @Test
    fun `ASymmetrickey is converted correctly`() {
        val csk = conversionService.convert(testAsymmetricKey, nl.nuts.consent.model.ASymmetricKey::class.java)!!
        assertEquals(testAsymmetricKey.cipherText, csk.cipherText)
        assertEquals(testAsymmetricKey.legalEntity, csk.legalEntityURI)
        assertEquals(testAsymmetricKey.alg, csk.alg)
    }

    @Test
    fun `Metadata is converted correctly`() {
        val m = conversionService.convert(testMetadata, nl.nuts.consent.model.ConsentMetadata::class.java)!!
        assertEquals(LocalDate.now(), m.period.validFrom)
        assertEquals("medical", m.domain.first().name)
        assertEquals(testSymmetricKey.iv, m.secureKey.iv)
        assertEquals(testAsymmetricKey.alg, m.organisationSecureKeys.first().alg)
    }
}