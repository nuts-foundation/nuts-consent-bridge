package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param externalId Unique hexadecimal identifier created based on BSN and private key of care provider.
 * @param UUID Unique identifier assigned by the consent cordapp
 */
data class ConsentId (

        @JsonProperty("externalId") val externalId: String? = null,

        @JsonProperty("UUID") val UUID: String? = null
) {

}

