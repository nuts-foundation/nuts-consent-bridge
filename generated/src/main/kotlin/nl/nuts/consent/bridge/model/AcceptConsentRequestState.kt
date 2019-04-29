package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import nl.nuts.consent.bridge.model.ConsentId
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param consentId 
 * @param partyAttachmentSignature 
 */
data class AcceptConsentRequestState (

        @get:NotNull 
        @JsonProperty("consentId") val consentId: ConsentId,

        @get:NotNull 
        @JsonProperty("partyAttachmentSignature") val partyAttachmentSignature: PartyAttachmentSignature
) {

}

