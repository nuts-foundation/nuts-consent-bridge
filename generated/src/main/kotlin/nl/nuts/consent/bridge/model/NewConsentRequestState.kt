package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.Metadata
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param externalId 
 * @param metadata 
 * @param attachment Base64 encoded data
 */
data class NewConsentRequestState (

        @get:NotNull 
        @JsonProperty("externalId") val externalId: String,

        @get:NotNull 
        @JsonProperty("metadata") val metadata: Metadata,

        @get:NotNull 
        @JsonProperty("attachment") val attachment: String
) {

}

