package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param externalId 
 * @param UUID Unique identifier assigned by the consent cordapp
 */
data class ConsentId (

        @get:NotNull 
        @JsonProperty("externalId") val externalId: String,

        @get:NotNull 
        @JsonProperty("UUID") val UUID: String
) {

}

