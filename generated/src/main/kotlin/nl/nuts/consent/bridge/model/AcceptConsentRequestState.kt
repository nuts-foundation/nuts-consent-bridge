package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param externalId 
 * @param attachment Base64 SecureHash value
 */
data class AcceptConsentRequestState (

        @get:NotNull 
        @JsonProperty("externalId") val externalId: String,

        @get:NotNull 
        @JsonProperty("attachment") val attachment: String
) {

}

