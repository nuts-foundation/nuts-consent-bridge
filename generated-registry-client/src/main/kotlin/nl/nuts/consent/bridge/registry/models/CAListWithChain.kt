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
 * 
 * @param chain list of certificates, roots first then intermediates, shared amongst all CAs. PEM encoded.
 * @param caList list of current active (or will be active) vendor CAs. PEM encoded
 */
data class CAListWithChain (
    /* list of certificates, roots first then intermediates, shared amongst all CAs. PEM encoded. */
    val chain: kotlin.Array<kotlin.String>,
    /* list of current active (or will be active) vendor CAs. PEM encoded */
    val caList: kotlin.Array<kotlin.String>
) {

}

