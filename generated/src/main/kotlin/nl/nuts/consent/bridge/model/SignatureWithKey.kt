package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.JWK
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param publicKey 
 * @param &#x60;data&#x60; base64 encoded bytes
 */
data class SignatureWithKey (

        @get:NotNull 
        @JsonProperty("publicKey") val publicKey: JWK,

        @get:NotNull 
        @JsonProperty("data") val `data`: String
) {

}

