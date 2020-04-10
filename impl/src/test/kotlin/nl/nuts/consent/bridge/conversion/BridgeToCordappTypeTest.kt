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
import nl.nuts.consent.bridge.model.*
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.time.OffsetDateTime
import java.util.*
import kotlin.test.*

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
    fun `PAS with JWS`() {
        val pubKeyPem = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0WKtAW6diQypS4WT+kjoATMhEDlc/FWOyTUEPQ/cI1c/pD4MO0I75nW0/rdoY1trz2Tr2TmV+fccGt6uxmryw4iZTrVvpHV8O0T3bEmY3ubzeN9jjdz/seLnOrVyXM11s7ris7ozmw87Z/WzCVv8D/qQkQOsyUGwKUXgAzw3xXsQA7w9q6oS/IRenSFIg9+uGhe3rwenhBBvlo9Pb/dKGyAc2qaEICnN34H2+hl9+d6GQVwHyDS8EPW+FjKov1c+qpkdIU3j3H0E0QOfYvE8ltfNchZdvUnj0Vs2O0gS2KzvI6tgiYVxbP+hGNYA2i37DLfgmyu1624JQQnX4KaH6wIDAQAB"
        val expectedPublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubKeyPem)))
        val jws = "eyJqd2siOnsiZSI6IkFRQUIiLCJrdHkiOiJSU0EiLCJuIjoiMFdLdEFXNmRpUXlwUzRXVC1ram9BVE1oRURsY19GV095VFVFUFFfY0kxY19wRDRNTzBJNzVuVzBfcmRvWTF0cnoyVHIyVG1WLWZjY0d0NnV4bXJ5dzRpWlRyVnZwSFY4TzBUM2JFbVkzdWJ6ZU45ampkel9zZUxuT3JWeVhNMTFzN3Jpczdvem13ODdaX1d6Q1Z2OERfcVFrUU9zeVVHd0tVWGdBenczeFhzUUE3dzlxNm9TX0lSZW5TRklnOS11R2hlM3J3ZW5oQkJ2bG85UGJfZEtHeUFjMnFhRUlDbk4zNEgyLWhsOS1kNkdRVndIeURTOEVQVy1GaktvdjFjLXFwa2RJVTNqM0gwRTBRT2ZZdkU4bHRmTmNoWmR2VW5qMFZzMk8wZ1MyS3p2STZ0Z2lZVnhiUC1oR05ZQTJpMzdETGZnbXl1MTYyNEpRUW5YNEthSDZ3In0sImFsZyI6IlJTMjU2In0.aGVsbG8.VlOfw2RxcOtFPoPOldOg1Y6iGdRxIXV7yrfKIZ19l7z9WP6Iz9KzGrNuZUnpTxJ2KoJj3Mb8wrAHatfWxpSeaokTLFr74TvxwlrzHMLg-EzF4njahz01IMYKtHefKk9yHa1Tu1acoMeenOR3cwqP95xwHjt9VWElFIqC0VVqc_WuKx6CoYKRZS234P9XDeKnlEmFh--COpd6ppbKGWU4LvpqlcTj4IMQYjpzURPEldmvij0QRHPQOzkkxmfIZjmEr6f_a07X7GUTcN6cjM4qdN1JmXA5iEM0mTc8vzs8T38KjKPKNa4IoVtBgUnThC7yUzlV9vn0vh_kWZ9lsJYkZg"
        val expected = PartyAttachmentSignature("entity", "CAFEBABE".repeat(8), jws)
        val (entity, hash, sig) = BridgeToCordappType.convert(expected)
        assertEquals(expected.legalEntity, entity)
        assertEquals(expected.attachment, hash.toString())
        assertEquals(expectedPublicKey, sig.by)
    }

    @Test
    fun `PAS with SignatureWithKey`() {
        val pubKeyPem = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0WKtAW6diQypS4WT+kjoATMhEDlc/FWOyTUEPQ/cI1c/pD4MO0I75nW0/rdoY1trz2Tr2TmV+fccGt6uxmryw4iZTrVvpHV8O0T3bEmY3ubzeN9jjdz/seLnOrVyXM11s7ris7ozmw87Z/WzCVv8D/qQkQOsyUGwKUXgAzw3xXsQA7w9q6oS/IRenSFIg9+uGhe3rwenhBBvlo9Pb/dKGyAc2qaEICnN34H2+hl9+d6GQVwHyDS8EPW+FjKov1c+qpkdIU3j3H0E0QOfYvE8ltfNchZdvUnj0Vs2O0gS2KzvI6tgiYVxbP+hGNYA2i37DLfgmyu1624JQQnX4KaH6wIDAQAB"
        val expectedPublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubKeyPem)))
        val expectedJWK = PublicJsonWebKey.Factory.newPublicJwk(expectedPublicKey).toParams(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)

        val expected = PartyAttachmentSignature("entity", "CAFEBABE".repeat(8), SignatureWithKey(expectedJWK, "ABCDEF"))
        val (entity, hash, sig) = BridgeToCordappType.convert(expected)
        assertEquals(expected.legalEntity, entity)
        assertEquals(expected.attachment, hash.toString())
        assertEquals(expectedPublicKey, sig.by)
    }

    @Test
    fun `PAS with unsupported signature type`() {
        val expected = PartyAttachmentSignature("entity", "CAFEBABE".repeat(8), mapOf<String, String>())
        assertFailsWith<IllegalArgumentException> {
            BridgeToCordappType.convert(expected)
        }
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