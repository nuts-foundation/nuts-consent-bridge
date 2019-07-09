package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.SignatureWithKey
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param legalEntity Generic identifier used for representing BSN, agbcode, etc. It's always constructed as an URN followed by a double colon (:) and then the identifying value of the given URN 
 * @param attachment Hexidecimal SecureHash value
 * @param signature 
 */
data class PartyAttachmentSignature (

        @get:NotNull 
        @JsonProperty("legalEntity") val legalEntity: String,

        @get:NotNull 
        @JsonProperty("attachment") val attachment: String,

        @get:NotNull 
        @JsonProperty("signature") val signature: SignatureWithKey
) {

}

