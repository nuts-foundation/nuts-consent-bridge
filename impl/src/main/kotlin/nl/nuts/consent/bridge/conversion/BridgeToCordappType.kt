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
import nl.nuts.consent.contract.AttachmentSignature
import nl.nuts.consent.model.*
import org.bouncycastle.util.io.pem.PemReader
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.StringReader
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.*

class BridgeToCordappType {
    companion object {

        fun <T : ConsentMetadata> convert(source: Metadata): ConsentMetadata {
            return ConsentMetadata(
                    domain = source.domain.map { convert<Domain>(it)!! },
                    secureKey = convert<SymmetricKey>(source.secureKey)!!,
                    organisationSecureKeys = source.organisationSecureKeys.map { convert<ASymmetricKey>(it)!! },
                    period = convert<Period>(source.period)!!
            )
        }

        fun <T : ASymmetricKey> convert(source: nl.nuts.consent.bridge.model.ASymmetricKey): ASymmetricKey {
            if (source.alg == null || source.cipherText == null ) {
                throw IllegalArgumentException("alg and cipherText are required in ASymmetricKey")
            }

            return ASymmetricKey(
                    alg = source.alg!!,
                    cipherText = source.cipherText!!,
                    legalEntity = source.legalEntity
            )
        }

        fun <T : Domain> convert(source: nl.nuts.consent.bridge.model.Domain) : Domain{
            return Domain.valueOf(source.name)
        }

        fun <T :Period> convert(source: nl.nuts.consent.bridge.model.Period) : Period{
            return if (source.validTo == null) {
                Period(validFrom = source.validFrom.toLocalDate())
            } else {
                Period(validFrom = source.validFrom.toLocalDate(), validTo = source.validTo!!.toLocalDate())
            }
        }

        fun <T : SymmetricKey> convert(source: nl.nuts.consent.bridge.model.SymmetricKey) : SymmetricKey{
            return SymmetricKey(
                    alg = source.alg,
                    iv = source.iv
            )
        }

        fun <T :AttachmentSignature> convert(source: PartyAttachmentSignature) : AttachmentSignature{
            try {
                val reader = PemReader(StringReader(source.signature.publicKey))
                val pemObject = reader.readPemObject() ?: throw IllegalArgumentException("Exception on parsing PartyAttachmentSignature.signature.publicKey")

                val keySpec = X509EncodedKeySpec(pemObject.content)
                val factory = KeyFactory.getInstance("RSA")
                val pk = factory.generatePublic(keySpec)

                return AttachmentSignature(source.legalEntity, SecureHash.parse(source.attachment), DigitalSignature.WithKey(pk, Base64.getDecoder().decode(source.signature.data)))
            } catch(e : IOException) {
                throw IllegalArgumentException("Exception on converting PartyAttachmentSignature: ${e.message}", e)
            }
        }


    }
}
