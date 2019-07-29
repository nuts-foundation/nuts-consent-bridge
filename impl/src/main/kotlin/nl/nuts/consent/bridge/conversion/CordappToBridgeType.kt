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

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.DigitalSignature
import nl.nuts.consent.bridge.model.ConsentId
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.model.SignatureWithKey
import nl.nuts.consent.bridge.model.SymmetricKey
import nl.nuts.consent.contract.AttachmentSignature
import nl.nuts.consent.model.ASymmetricKey
import nl.nuts.consent.model.ConsentMetadata
import nl.nuts.consent.model.Domain
import nl.nuts.consent.model.Period
import nl.nuts.consent.state.ConsentRequestState
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringWriter
import java.time.ZoneId
import java.util.*
import nl.nuts.consent.bridge.model.Metadata as BridgeMetadata

class CordappToBridgeType {
    companion object {

        fun convert(source: Period): nl.nuts.consent.bridge.model.Period {
            return nl.nuts.consent.bridge.model.Period(
                    validFrom = source.validFrom.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime(),
                    validTo = source.validTo?.atStartOfDay(ZoneId.systemDefault())?.toOffsetDateTime()
            )
        }

        fun convert(source: Domain): nl.nuts.consent.bridge.model.Domain {
            return nl.nuts.consent.bridge.model.Domain.valueOf(source.toString())
        }

        fun convert(source: nl.nuts.consent.model.SymmetricKey): SymmetricKey {
            return SymmetricKey(
                    alg = source.alg,
                    iv = source.iv
            )
        }


        fun convert(source: ASymmetricKey): nl.nuts.consent.bridge.model.ASymmetricKey {
            return nl.nuts.consent.bridge.model.ASymmetricKey(
                    legalEntity = source.legalEntity,
                    alg = source.alg,
                    cipherText = source.cipherText
            )
        }

        fun convert(source: ConsentMetadata): BridgeMetadata {
            return BridgeMetadata(
                    domain = source.domain.map { convert(it) },
                    period = convert(source.period),
                    secureKey = convert(source.secureKey),
                    organisationSecureKeys = source.organisationSecureKeys.map { convert(it) }
            )
        }

        fun convert(source: DigitalSignature.WithKey): SignatureWithKey {
            val stringWriter = StringWriter()
            val writer = PemWriter(stringWriter)
            writer.writeObject(PemObject("PUBLIC KEY", source.by.encoded))

            return SignatureWithKey(
                    publicKey = stringWriter.toString(),
                    data = Base64.getEncoder().encodeToString(source.bytes)
            )
        }

        fun convert(source: AttachmentSignature): PartyAttachmentSignature {
            return PartyAttachmentSignature(
                    legalEntity = source.legalEntityURI,
                    attachment = source.attachmentHash.toString(),
                    signature = convert(source.signature)
            )
        }

        fun convert(source: UniqueIdentifier): ConsentId {
            return ConsentId(
                    externalId = source.externalId,
                    UUID = source.id.toString()
            )
        }

        fun convert(source: ConsentRequestState): nl.nuts.consent.bridge.model.ConsentRequestState {
            return nl.nuts.consent.bridge.model.ConsentRequestState(
                    consentId = convert(source.consentStateUUID),
                    attachments = source.attachments.map { it.toString() },
                    legalEntities = source.legalEntities.toList(),
                    signatures = source.signatures.map { convert(it) }
            )
        }
    }
}