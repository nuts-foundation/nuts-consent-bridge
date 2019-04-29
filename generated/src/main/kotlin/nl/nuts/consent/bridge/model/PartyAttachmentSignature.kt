package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.SignatureWithKey
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param legalEntityURI 
 * @param attachment Hexidecimal SecureHash value
 * @param signature 
 */
data class PartyAttachmentSignature (

        @get:NotNull 
        @JsonProperty("legalEntityURI") val legalEntityURI: String,

        @get:NotNull 
        @JsonProperty("attachment") val attachment: String,

        @get:NotNull 
        @JsonProperty("signature") val signature: SignatureWithKey
) {

}

