package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.SignatureWithKey
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param party 
 * @param attachment Base64 SecureHash value
 * @param signature 
 */
data class PartySignatureAttachment (

        @get:NotNull 
        @JsonProperty("party") val party: String,

        @get:NotNull 
        @JsonProperty("attachment") val attachment: String,

        @get:NotNull 
        @JsonProperty("signature") val signature: SignatureWithKey
) {

}

