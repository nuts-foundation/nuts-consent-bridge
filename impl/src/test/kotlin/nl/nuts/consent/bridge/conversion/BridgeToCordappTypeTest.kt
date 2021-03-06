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

import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.SecureHash
import nl.nuts.consent.bridge.model.ASymmetricKey
import nl.nuts.consent.bridge.model.Domain
import nl.nuts.consent.bridge.model.Period
import nl.nuts.consent.bridge.model.SymmetricKey
import org.jose4j.jwk.PublicJsonWebKey
import org.junit.Test
import java.time.OffsetDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeToCordappTypeTest {
    val testPeriod = Period(validFrom = OffsetDateTime.now(), validTo = OffsetDateTime.now())
    val testDomain = Domain.medical
    val testSymmetricKey = SymmetricKey(alg = "AES_GCM", iv = "iv")
    val testAsymmetricKey = ASymmetricKey(legalEntity = "legal", alg = "alg", cipherText = "cipher")
    val testMetadata = nl.nuts.consent.bridge.model.Metadata(
            domain = listOf(testDomain),
            secureKey = testSymmetricKey,
            organisationSecureKeys = listOf(testAsymmetricKey),
            period = testPeriod,
            previousAttachmentHash = "hash",
            consentRecordHash = "hash"
    )

    @Test
    fun `Period is converted correctly`() {
        val cPeriod = BridgeToCordappType.convert(testPeriod)
        assertEquals(testPeriod.validFrom, cPeriod.validFrom)
        assertEquals(testPeriod.validTo, cPeriod.validTo)
    }

    @Test
    fun `Period without validTo is converted correctly`() {
        val testPeriod = Period(validFrom = OffsetDateTime.now())
        val cPeriod = BridgeToCordappType.convert(testPeriod)
        assertEquals(testPeriod.validFrom, cPeriod.validFrom)
        assertNull(cPeriod.validTo)
    }

    @Test
    fun `Domain is converted correctly`() {
        val cDomain = BridgeToCordappType.convert(testDomain)
        assertEquals("medical", cDomain.name)
    }

    @Test
    fun `Symmetrickey is converted correctly`() {
        val csk = BridgeToCordappType.convert(testSymmetricKey)
        assertEquals(testSymmetricKey.iv, csk.iv)
        assertEquals(testSymmetricKey.alg, csk.alg)

    }

    @Test
    fun `ASymmetrickey is converted correctly`() {
        val csk = BridgeToCordappType.convert(testAsymmetricKey)
        assertEquals(testAsymmetricKey.cipherText, csk.cipherText)
        assertEquals(testAsymmetricKey.legalEntity, csk.legalEntity)
        assertEquals(testAsymmetricKey.alg, csk.alg)
    }

    @Test
    fun `Metadata is converted correctly`() {
        val m = BridgeToCordappType.convert(testMetadata)
        assertEquals(testPeriod.validFrom, m.period.validFrom)
        assertEquals("medical", m.domain.first().name)
        assertEquals(testSymmetricKey.iv, m.secureKey.iv)
        assertEquals(testAsymmetricKey.alg, m.organisationSecureKeys.first().alg)
        assertEquals("hash", m.previousAttachmentId)
        assertEquals("hash", m.consentRecordHash)
    }

    @Test
    fun `signature is correct from cross-language case`() {
        val attHex = "4D15851551A9E5DAF8114C98D0F8D4B18CC97ABD31424D5EA9E3CC84C5F9B45C"
        val base64Sign = "QeztwzJgxCuW+ZlUsUyFn7zESuyEFpPCP546hJdcXarzvsWWuTzA3RFLOIJJRqjz7sccGAcidi+rKDlI1Rj4gOSFLhJKkOABXLt+X2kcqpDguta5/i03j4jAN0dI2Sanp5gc7AHJ0r4791KEYrEbve6rVGN6kSd7kvWFyfTtFgD4R+Yp4T3e5oG5yMFdAmiNK8ko6o8nmzoY0yOWdHneUFaAjGAPkGGGsspQ7U3UYAyVdkXdspF4Ryeh8LbbePFSQkO6Pzj9gVMWBY1LrGIRSPhGQEXj7P6PTar8gs/AkX5gyAQLS383MEcg3fCOiEAbRgQLYsRgo04hl3IChfOW2w=="
        val jwkJson = "{\"kty\":\"RSA\",\"e\":\"AQAB\",\"kid\":\"17bf8a6f-0a0a-4bce-878c-4ac9b7447c64\",\"n\":\"wm7FBfggHaAfapO7TdFv0OwS-Ip9Wi7gyhddjmdZBZDzfYMUPr4-0utGM3Ry8JtCfxmsHL3ZmvG04GV1doeCLjLywm6OFfoEQCpliRiCyarpd2MrxKWjkSwOl9MJdVm3xpb7BWJdXkKEwoU4lBk8cZPay32juPzAV5eb6UCnq53PZ5O0H80J02oPLpBs2D6ASjUQpRf2xP0bvaP2W92PZYzJwrSA3zdxPmrMVApOoIZL7OHBE-y0I9ZUt-zmxD8TzRdN9Etf9wjLD7psu9aL_XHIHR0xMkYV8cr_nCbJ6H0PbDd3yIQvYPjLEVS5LeieN-DzIlYO6Y7kpws6k0rxew\"}"

        val secureHash = SecureHash.parse(attHex)
        val jwk = PublicJsonWebKey.Factory.newPublicJwk(jwkJson)
        val digSign = DigitalSignature.WithKey(jwk.publicKey, Base64.getDecoder().decode(base64Sign))

        assertTrue(digSign.isValid(secureHash.bytes))
    }
}