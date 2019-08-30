package nl.nuts.consent.bridge.model

import com.fasterxml.jackson.annotation.JsonProperty
import javax.validation.constraints.NotNull

/**
 * 
 * @param domain 
 * @param secureKey 
 * @param organisationSecureKeys 
 * @param period 
 */
data class Metadata (

        @get:NotNull 
        @JsonProperty("domain") val domain: List<Domain>,

        @get:NotNull 
        @JsonProperty("secureKey") val secureKey: SymmetricKey,

        @get:NotNull 
        @JsonProperty("organisationSecureKeys") val organisationSecureKeys: List<ASymmetricKey>,

        @get:NotNull 
        @JsonProperty("period") val period: Period
) {

}

