/**
* Nuts event store spec
* API specification for event store. The event store records the events of the in-flight transactions. 
*
* OpenAPI spec version: 0.1.0
* 
*
* NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
* https://openapi-generator.tech
* Do not edit the class manually.
*/
package nl.nuts.consent.bridge.events.models


import com.squareup.moshi.Json
/**
 * 
 * @param uuid V4 UUID
 * @param name 
 * @param retryCount 0 to X
 * @param externalId ID calculated by crypto using BSN and private key of initiatorLegalEntity
 * @param consentId V4 UUID assigned by Corda to a record
 * @param transactionId V4 UUID assigned by Corda to a transaction
 * @param initiatorLegalEntity Generic identifier used for representing BSN, agbcode, etc. It's always constructed as an URN followed by a double colon (:) and then the identifying value of the given URN 
 * @param payload NewConsentRequestState JSON as accepted by consent-bridge (:ref:`nuts-consent-bridge-api`)
 * @param error error reason in case of a functional error
 */
data class Event (
    /* V4 UUID */
    val uuid: kotlin.String,
    val name: Event.Name,
    /* 0 to X */
    val retryCount: kotlin.Int,
    /* ID calculated by crypto using BSN and private key of initiatorLegalEntity */
    val externalId: kotlin.String,
    /* Generic identifier used for representing BSN, agbcode, etc. It's always constructed as an URN followed by a double colon (:) and then the identifying value of the given URN  */
    val initiatorLegalEntity: kotlin.String,
    /* NewConsentRequestState JSON as accepted by consent-bridge (:ref:`nuts-consent-bridge-api`) */
    val payload: kotlin.String,
    /* V4 UUID assigned by Corda to a record */
    val consentId: kotlin.String? = null,
    /* V4 UUID assigned by Corda to a transaction */
    val transactionId: kotlin.String? = null,
    /* error reason in case of a functional error */
    val error: kotlin.String? = null
) {

    /**
    * 
    * Values: consentRequestConstructed,consentRequestInFlight,consentRequestFlowErrored,consentRequestFlowSuccess,distributedConsentRequestReceived,allSignaturesPresent,consentRequestInFlightForFinalState,consentRequestValid,consentRequestAcked,consentRequestNacked,attachmentSigned,consentDistributed,completed,error
    */
    enum class Name(val value: kotlin.String){
    
        @Json(name = "consentRequest constructed") consentRequestConstructed("consentRequest constructed"),
    
        @Json(name = "consentRequest in flight") consentRequestInFlight("consentRequest in flight"),
    
        @Json(name = "consentRequest flow errored") consentRequestFlowErrored("consentRequest flow errored"),
    
        @Json(name = "consentRequest flow success") consentRequestFlowSuccess("consentRequest flow success"),
    
        @Json(name = "distributed ConsentRequest received") distributedConsentRequestReceived("distributed ConsentRequest received"),
    
        @Json(name = "all signatures present") allSignaturesPresent("all signatures present"),
    
        @Json(name = "consentRequest in flight for final state") consentRequestInFlightForFinalState("consentRequest in flight for final state"),
    
        @Json(name = "consentRequest valid") consentRequestValid("consentRequest valid"),
    
        @Json(name = "consentRequest acked") consentRequestAcked("consentRequest acked"),
    
        @Json(name = "consentRequest nacked") consentRequestNacked("consentRequest nacked"),
    
        @Json(name = "attachment signed") attachmentSigned("attachment signed"),
    
        @Json(name = "consent distributed") consentDistributed("consent distributed"),
    
        @Json(name = "completed") completed("completed"),
    
        @Json(name = "error") error("error");
    
    }

}

