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

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.SecureHash
import nl.nuts.consent.bridge.model.ConsentId
import nl.nuts.consent.bridge.model.ConsentRequestState
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.model.SignatureWithKey
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import org.bouncycastle.util.io.pem.PemWriter
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*

/**
 * Converter methods for converting Corda model to Nuts model
 */
fun nl.nuts.consent.state.ConsentRequestState.convert() : ConsentRequestState {
    return ConsentRequestState(
            this.consentStateUUID.convert(),
            this.attachments.map { it.toString() },
            this.signatures.map { it.convert() })
}

fun UniqueIdentifier.convert() : ConsentId {
    return ConsentId(this.externalId!!, this.id.toString())
}

fun nl.nuts.consent.contract.AttachmentSignature.convert() : PartyAttachmentSignature {
    return PartyAttachmentSignature(this.legalEntityURI, this.attachmentHash.toString(), this.signature.convert())
}

//fun ConsentId.convert() : UniqueIdentifier {
//    return UniqueIdentifier(this.externalId, java.util.UUID.fromString(this.UUID))
//}

fun PartyAttachmentSignature.convert() : nl.nuts.consent.contract.AttachmentSignature {
    try {
        val reader = PemReader(StringReader(this.signature.publicKey))
        val pemObject = reader.readPemObject() ?: throw IllegalArgumentException("Exception on parsing PartyAttachmentSignature.signature.publicKey")

        val keySpec = X509EncodedKeySpec(pemObject.content)
        val factory = KeyFactory.getInstance("RSA")
        val pk = factory.generatePublic(keySpec)

        return nl.nuts.consent.contract.AttachmentSignature(this.legalEntity, SecureHash.parse(this.attachment), DigitalSignature.WithKey(pk, Base64.getDecoder().decode(this.signature.data)))
    } catch(e :IOException) {
        throw IllegalArgumentException("Exception on converting PartyAttachmentSignature: ${e.message}", e)
    }
}

fun DigitalSignature.WithKey.convert() : SignatureWithKey {
    val stringWriter = StringWriter()
    val writer = PemWriter(stringWriter)
    writer.writeObject(PemObject("PUBLIC KEY", this.by.encoded))

    return SignatureWithKey(stringWriter.toString(), Base64.getEncoder().encodeToString(this.bytes))
}