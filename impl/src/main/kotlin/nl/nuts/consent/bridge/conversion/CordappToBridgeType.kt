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
import nl.nuts.consent.bridge.model.*
import nl.nuts.consent.contract.AttachmentSignature
import nl.nuts.consent.model.ASymmetricKey
import nl.nuts.consent.model.ConsentMetadata
import nl.nuts.consent.model.Domain
import nl.nuts.consent.model.Period
import nl.nuts.consent.state.ConsentBranch
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import java.io.StringWriter
import java.time.ZoneId
import java.util.*
import nl.nuts.consent.bridge.model.Metadata as BridgeMetadata

/**
 * Utility class to convert consent-cordapp types to consent-bridge types
 */
class CordappToBridgeType {
    companion object {

        /**
         * Convert Period between formats
         *
         * @param source consent-cordapp model
         * @return bridge model
         */
        fun convert(source: Period): nl.nuts.consent.bridge.model.Period {
            return nl.nuts.consent.bridge.model.Period(
                    validFrom = source.validFrom,
                    validTo = source.validTo
            )
        }

        /**
         * Convert Domain between formats
         *
         * @param source consent-cordapp model
         * @return bridge model
         */
        fun convert(source: Domain): nl.nuts.consent.bridge.model.Domain {
            return nl.nuts.consent.bridge.model.Domain.valueOf(source.toString())
        }

        /**
         * Convert SymmetricKey between formats
         *
         * @param source consent-cordapp model
         * @return bridge model
         */
        fun convert(source: nl.nuts.consent.model.SymmetricKey): SymmetricKey {
            return SymmetricKey(
                    alg = source.alg,
                    iv = source.iv
            )
        }

        /**
         * Convert ASymmetricKey between formats
         *
         * @param source consent-cordapp model
         * @return bridge model
         */
        fun convert(source: ASymmetricKey): nl.nuts.consent.bridge.model.ASymmetricKey {
            return nl.nuts.consent.bridge.model.ASymmetricKey(
                    legalEntity = source.legalEntity,
                    alg = source.alg,
                    cipherText = source.cipherText
            )
        }

        /**
         * Convert Metadata between formats
         *
         * @param source consent-cordapp model
         * @return bridge model
         */
        fun convert(source: ConsentMetadata): BridgeMetadata {
            return BridgeMetadata(
                    domain = source.domain.map { convert(it) },
                    period = convert(source.period),
                    secureKey = convert(source.secureKey),
                    organisationSecureKeys = source.organisationSecureKeys.map { convert(it) },
                    previousAttachmentHash = source.previousAttachmentId,
                    consentRecordHash = source.consentRecordHash
            )
        }

        /**
         * Convert SignatureWithKey between formats
         *
         * Used as part of the (Party)AttachmentSignature
         *
         * @param source consent-cordapp model
         * @return bridge model
         */
        fun convert(source: DigitalSignature.WithKey): SignatureWithKey {
            val jwk = PublicJsonWebKey.Factory.newPublicJwk(source.by)

            return SignatureWithKey(
                    publicKey = jwk.toParams(JsonWebKey.OutputControlLevel.PUBLIC_ONLY),
                    data = Base64.getEncoder().encodeToString(source.bytes)
            )
        }
        /**
         * Convert AttachmentSignature between formats
         *
         * @param source consent-cordapp model
         * @return bridge model
         */
        fun convert(source: AttachmentSignature): PartyAttachmentSignature {
            return PartyAttachmentSignature(
                    legalEntity = source.legalEntityURI,
                    attachment = source.attachmentHash.toString(),
                    signature = convert(source.signature)
            )
        }

        /**
         * Convert ConsentId/UniqueIdentifier between formats
         *
         * @param source consent-cordapp model
         * @return bridge model
         */
        fun convert(source: UniqueIdentifier): ConsentId {
            return ConsentId(
                    externalId = source.externalId,
                    UUID = source.id.toString()
            )
        }

        /**
         * Convert ConsentRequestState between formats
         *
         * This will exclude the cipherText and metadata!
         *
         * @param source consent-cordapp model
         * @return bridge model
         */
        fun convert(source: ConsentBranch): FullConsentRequestState {
            val consentRecords = mutableListOf<ConsentRecord>()

            source.attachments.forEach { att ->
                val hash = att.toString()
                consentRecords.add(ConsentRecord(
                        attachmentHash = hash,
                        signatures = source.signatures.filter { sig -> sig.attachmentHash.toString() == hash }.map { CordappToBridgeType.convert(it) }
                ))
            }

            return FullConsentRequestState(
                    consentId = convert(source.linearId),
                    legalEntities = source.legalEntities.toList(),
                    consentRecords = consentRecords
            )
        }
    }
}