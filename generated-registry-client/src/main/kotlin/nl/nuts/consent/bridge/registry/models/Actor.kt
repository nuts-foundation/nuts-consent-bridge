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


/**
 * A person that acts on behalf of an organization
 * @param identifier Generic identifier used for representing BSN, agbcode, etc. It's always constructed as an URN followed by a double colon (:) and then the identifying value of the given URN 
 */
data class Actor (
    /* Generic identifier used for representing BSN, agbcode, etc. It's always constructed as an URN followed by a double colon (:) and then the identifying value of the given URN  */
    val identifier: kotlin.String
) {

}

