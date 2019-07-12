package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.ConsentId
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param consentId 
 * @param attachments 
 * @param signatures 
 * @param legalEntities 
 */
data class ConsentRequestState (

        @get:NotNull 
        @JsonProperty("consentId") val consentId: ConsentId,

        @get:NotNull 
        @JsonProperty("attachments") val attachments: List<String>,

        @get:NotNull 
        @JsonProperty("signatures") val signatures: List<PartyAttachmentSignature>,

        @get:NotNull 
        @JsonProperty("legalEntities") val legalEntities: List<String>
) {

}

