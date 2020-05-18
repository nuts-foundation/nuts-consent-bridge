package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.ConsentId
import nl.nuts.consent.bridge.model.ConsentRecord
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param consentId 
 * @param consentRecords 
 * @param legalEntities 
 * @param initiatingLegalEntity Generic identifier used for representing BSN, agbcode, etc. It's always constructed as an URN followed by a colon (:) and then the identifying value of the given URN 
 * @param initiatingNode The X500 name of the node that initiated the transaction
 * @param requestDateTime the date-time when the request was made
 */
data class FullConsentRequestState (

        @get:NotNull 
        @JsonProperty("consentId") val consentId: ConsentId,

        @get:NotNull 
        @JsonProperty("consentRecords") val consentRecords: List<ConsentRecord>,

        @get:NotNull 
        @JsonProperty("legalEntities") val legalEntities: List<String>,

        @get:NotNull 
        @JsonProperty("initiatingLegalEntity") val initiatingLegalEntity: String,

        @JsonProperty("initiatingNode") val initiatingNode: String? = null,

        @JsonProperty("requestDateTime") val requestDateTime: java.time.OffsetDateTime? = null
) {

}

