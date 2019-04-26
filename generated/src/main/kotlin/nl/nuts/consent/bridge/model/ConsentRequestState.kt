package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.PartySignatureAttachment
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param externalId 
 * @param attachments 
 * @param signatures 
 * @param parties 
 */
data class ConsentRequestState (

        @get:NotNull 
        @JsonProperty("externalId") val externalId: String,

        @get:NotNull 
        @JsonProperty("attachments") val attachments: List<String>,

        @get:NotNull 
        @JsonProperty("signatures") val signatures: List<PartySignatureAttachment>,

        @get:NotNull 
        @JsonProperty("parties") val parties: List<String>
) {

}

