package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param legalEntityURI 
 * @param alg 
 * @param cipherText base64 encoded
 */
data class ASymmetricKey (

        @get:NotNull 
        @JsonProperty("legalEntityURI") val legalEntityURI: String,

        @JsonProperty("alg") val alg: String? = null,

        @JsonProperty("cipherText") val cipherText: String? = null
) {

}

