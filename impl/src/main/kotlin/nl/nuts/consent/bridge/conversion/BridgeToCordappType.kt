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
import nl.nuts.consent.bridge.model.Metadata
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.model.SignatureWithKey
import nl.nuts.consent.contract.AttachmentSignature
import nl.nuts.consent.model.*
import org.jose4j.base64url.Base64Url
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jws.JsonWebSignature
import java.io.IOException
import java.util.*

/**
 * Utility class to convert consent-bridge types (from api) to consent-cordapp types
 */
class BridgeToCordappType {
    companion object {

        /**
         * Convert the metadata between formats
         * @param source metadata
         * @return cordapp ConsentMetadata
         */
        fun convert(source: Metadata): ConsentMetadata {
            return ConsentMetadata(
                    domain = source.domain.map { convert(it) },
                    secureKey = convert(source.secureKey),
                    organisationSecureKeys = source.organisationSecureKeys.map { convert(it) },
                    period = convert(source.period),
                    previousAttachmentId = source.previousAttachmentHash,
                    consentRecordHash = source.consentRecordHash
            )
        }

        /**
         * convert ASymmetricKey between formats
         * Some of the consent-cordapp properties must be non-null
         *
         * @param source bridge type
         * @return consent-cordapp type
         * @throws IllegalArgumentException when alg or cipherText is null
         */
        fun convert(source: nl.nuts.consent.bridge.model.ASymmetricKey): ASymmetricKey {
            val ct = source.cipherText ?: throw IllegalArgumentException("cipherText is required in ASymmetricKey")
            val a = source.alg ?: throw IllegalArgumentException("alg is required in ASymmetricKey")

            return ASymmetricKey(
                    alg = a,
                    cipherText = ct,
                    legalEntity = source.legalEntity
            )
        }

        /**
         * convert Domain between formats
         *
         * @param source bridge type
         * @return consent-cordapp type
         */
        fun convert(source: nl.nuts.consent.bridge.model.Domain) : Domain{
            return Domain.valueOf(source.name)
        }

        /**
         * convert Period between formats
         *
         * @param source bridge type
         * @return consent-cordapp type
         */
        fun convert(source: nl.nuts.consent.bridge.model.Period) : Period{
            return Period(validFrom = source.validFrom, validTo = source.validTo)
        }

        /**
         * convert SymmetricKey between formats
         *
         * @param source bridge type
         * @return consent-cordapp type
         */
        fun convert(source: nl.nuts.consent.bridge.model.SymmetricKey) : SymmetricKey{
            return SymmetricKey(
                    alg = source.alg,
                    iv = source.iv
            )
        }

        /**
         * convert PartyAttachmentSignature between formats.
         * This also converts the bridge JWK format to the cordapp X509 format
         *
         * @param source bridge type
         * @return consent-cordapp type
         */
        fun convert(source: PartyAttachmentSignature) : AttachmentSignature{
            try {
                var signature: DigitalSignature.WithKey
                if (source.signature is String) {
                    // Parse as JWS
                    val jws = JsonWebSignature().apply { compactSerialization = source.signature as String }
                    val sigBytes = jws.encodedSignature.let(Base64Url::decode)
                    signature = DigitalSignature.WithKey(jws.jwkHeader.publicKey, sigBytes)
                } else if (source.signature is SignatureWithKey) {
                    val sigWithKey = source.signature as SignatureWithKey
                    val jwk = PublicJsonWebKey.Factory.newPublicJwk(sigWithKey.publicKey)
                    signature = DigitalSignature.WithKey(jwk.publicKey, Base64.getDecoder().decode(sigWithKey.data))
                } else {
                    throw IOException("Signature is not a JWS or SignatureWithKey")
                }
                return AttachmentSignature(source.legalEntity, SecureHash.parse(source.attachment), signature)
            } catch(e : IOException) {
                throw IllegalArgumentException("Exception on converting PartyAttachmentSignature: ${e.message}", e)
            }
        }
    }
}
