package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.ASymmetricKey
import nl.nuts.consent.bridge.model.Domain
import nl.nuts.consent.bridge.model.Period
import nl.nuts.consent.bridge.model.SymmetricKey
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param domain 
 * @param secureKey 
 * @param organisationSecureKeys 
 * @param period 
 * @param previousAttachmentHash SHA256 of cipherText bytes
 */
data class Metadata (

        @get:NotNull 
        @JsonProperty("domain") val domain: List<Domain>,

        @get:NotNull 
        @JsonProperty("secureKey") val secureKey: SymmetricKey,

        @get:NotNull 
        @JsonProperty("organisationSecureKeys") val organisationSecureKeys: List<ASymmetricKey>,

        @get:NotNull 
        @JsonProperty("period") val period: Period,

        @JsonProperty("previousAttachmentHash") val previousAttachmentHash: String? = null
) {

}

