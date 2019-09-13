package nl.nuts.consent.bridge.model

import com.fasterxml.jackson.annotation.JsonProperty
import javax.validation.constraints.NotNull

/**
 * 
 * @param consentId 
 * @param consentRecords 
 * @param legalEntities 
 */
data class FullConsentRequestState (

        @get:NotNull 
        @JsonProperty("consentId") val consentId: ConsentId,

        @get:NotNull 
        @JsonProperty("legalEntities") val legalEntities: List<String>,

        @JsonProperty("consentRecords") val consentRecords: List<ConsentRecord>? = null
) {

}

