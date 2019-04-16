package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.ConsentRequestMetadata
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param consentRequestMetadata 
 * @param attachment 
 */
data class InlineObject (

        @JsonProperty("consentRequestMetadata") val consentRequestMetadata: ConsentRequestMetadata? = null,

        @JsonProperty("attachment") val attachment: java.io.File? = null
) {

}

