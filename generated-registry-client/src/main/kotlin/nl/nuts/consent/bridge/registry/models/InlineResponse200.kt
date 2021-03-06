/**
* Nuts registry API spec
* API specification for RPC services available at the nuts-registry
*
* OpenAPI spec version: 0.1.0
* 
*
* NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
* https://openapi-generator.tech
* Do not edit the class manually.
*/
package nl.nuts.consent.bridge.registry.models

import nl.nuts.consent.bridge.registry.models.Event

/**
 * 
 * @param fix if true, the data in the registry needs fixing/upgrading.
 * @param events list of events that resulted from fixing the data, list may be empty
 */
data class InlineResponse200 (
    /* if true, the data in the registry needs fixing/upgrading. */
    val fix: kotlin.Boolean? = null,
    /* list of events that resulted from fixing the data, list may be empty */
    val events: kotlin.Array<Event>? = null
) {

}

