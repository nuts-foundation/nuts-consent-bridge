package nl.nuts.consent.bridge.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 
 * @param metadata 
 * @param cipherText Base64 encoded cipher_text.bin (fhir)
 * @param attachmentHash SHA256 of cipherText bytes
 * @param signatures 
 */
data class ConsentRecord (

        @JsonProperty("metadata") val metadata: Metadata? = null,

        @JsonProperty("cipherText") val cipherText: String? = null,

        @JsonProperty("attachmentHash") val attachmentHash: String? = null,

        @JsonProperty("signatures") val signatures: List<PartyAttachmentSignature>? = null
) {

}

