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
import net.corda.core.crypto.SecureHash
import nl.nuts.consent.bridge.model.ConsentId
import nl.nuts.consent.bridge.model.Metadata
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.model.SignatureWithKey
import nl.nuts.consent.contract.AttachmentSignature
import nl.nuts.consent.model.*
import nl.nuts.consent.state.ConsentRequestState
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import org.bouncycastle.util.io.pem.PemWriter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.*

@Component
class ConsentRequestStateToBridgeType : Converter<ConsentRequestState, nl.nuts.consent.bridge.model.ConsentRequestState> {
    override fun convert(source: ConsentRequestState): nl.nuts.consent.bridge.model.ConsentRequestState? {
        return nl.nuts.consent.bridge.model.ConsentRequestState(
                consentId = UniqueIdentifierToBridgeType().convert(source.consentStateUUID)!!,
                attachments = source.attachments.map{it.toString()},
                legalEntities = source.legalEntities,
                signatures = source.signatures.map{ AttachmentSignatureToBridgeType().convert(it)!! }
        )
    }
}

@Component
class UniqueIdentifierToBridgeType : Converter<UniqueIdentifier, ConsentId> {
    override fun convert(source: UniqueIdentifier): ConsentId? {
        return ConsentId(
                externalId = source.externalId,
                UUID = source.id.toString()
        )
    }
}

@Component
class AttachmentSignatureToBridgeType : Converter<AttachmentSignature, PartyAttachmentSignature> {
    override fun convert(source: AttachmentSignature): PartyAttachmentSignature? {
        return PartyAttachmentSignature(
                legalEntity = source.legalEntityURI,
                attachment = source.attachmentHash.toString(),
                signature = DigitalSignatureToBridgeType().convert(source.signature)!!
        )
    }
}

@Component
class DigitalSignatureToBridgeType : Converter<DigitalSignature.WithKey, SignatureWithKey> {
    override fun convert(source: DigitalSignature.WithKey): SignatureWithKey? {
        val stringWriter = StringWriter()
        val writer = PemWriter(stringWriter)
        writer.writeObject(PemObject("PUBLIC KEY", source.by.encoded))

        return SignatureWithKey(
                publicKey = stringWriter.toString(),
                data = Base64.getEncoder().encodeToString(source.bytes)
        )
    }
}