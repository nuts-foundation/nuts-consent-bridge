package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.ConsentId
import nl.nuts.consent.bridge.model.Metadata
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param consentId 
 * @param signatures 
 * @param legalEntities 
 * @param metadata 
 * @param cipherText Base64 encoded cipher_text.bin (fhir)
 */
data class FullConsentRequestState (

        @get:NotNull 
        @JsonProperty("consentId") val consentId: ConsentId,

        @get:NotNull 
        @JsonProperty("signatures") val signatures: List<PartyAttachmentSignature>,

        @get:NotNull 
        @JsonProperty("legalEntities") val legalEntities: List<String>,

        @get:NotNull 
        @JsonProperty("metadata") val metadata: Metadata,

        @get:NotNull 
        @JsonProperty("cipherText") val cipherText: String
) {

}

