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
package nl.nuts.consent.bridge.registry.apis

import nl.nuts.consent.bridge.registry.models.Endpoint
import nl.nuts.consent.bridge.registry.models.Event

import nl.nuts.consent.bridge.registry.infrastructure.*

class EndpointsApi(basePath: kotlin.String = "http://localhost") : ApiClient(basePath) {

    /**
    * Find endpoints based on organisation identifiers and type of endpoint (optional)
    * 
    * @param orgIds A list of organisation identifiers to query for. identifiers are Nuts Identifiers with proper escaping 
    * @param type The type of endpoint requested, eg Nuts or FHIR (optional, default to null)
    * @param strict only return successfull result if each given organisation has an endpoint of the requested type, otherwise 400 (optional, default to null)
    * @return kotlin.Array<Endpoint>
    */
    @Suppress("UNCHECKED_CAST")
    fun endpointsByOrganisationId(orgIds: kotlin.Array<kotlin.String>, type: kotlin.String, strict: kotlin.Boolean) : kotlin.Array<Endpoint> {
        val localVariableBody: kotlin.Any? = null
        val localVariableQuery: MultiValueMap = mapOf("orgIds" to toMultiValue(orgIds.toList(), "multi"), "type" to listOf("$type"), "strict" to listOf("$strict"))
        val localVariableHeaders: kotlin.collections.Map<kotlin.String,kotlin.String> = mapOf()
        val localVariableConfig = RequestConfig(
            RequestMethod.GET,
            "/api/endpoints",
            query = localVariableQuery,
            headers = localVariableHeaders
        )
        val response = request<kotlin.Array<Endpoint>>(
            localVariableConfig,
            localVariableBody
        )

        return when (response.responseType) {
            ResponseType.Success -> (response as Success<*>).data as kotlin.Array<Endpoint>
            ResponseType.Informational -> TODO()
            ResponseType.Redirection -> TODO()
            ResponseType.ClientError -> throw ClientException((response as ClientError<*>).body as? String ?: "Client error")
            ResponseType.ServerError -> throw ServerException((response as ServerError<*>).message ?: "Server error")
            else -> throw kotlin.IllegalStateException("Undefined ResponseType.")
        }
    }

    /**
    * Adds/updates an endpoint for this organisation to the registry. If the endpoint already exists (matched by endpoint ID) it is updated.
    * 
    * @param id URL encoded identifier 
    * @param endpoint  
    * @return Event
    */
    @Suppress("UNCHECKED_CAST")
    fun registerEndpoint(id: kotlin.String, endpoint: Endpoint) : Event {
        val localVariableBody: kotlin.Any? = endpoint
        val localVariableQuery: MultiValueMap = mapOf()
        val localVariableHeaders: kotlin.collections.Map<kotlin.String,kotlin.String> = mapOf()
        val localVariableConfig = RequestConfig(
            RequestMethod.POST,
            "/api/organization/{id}/endpoints".replace("{"+"id"+"}", "$id"),
            query = localVariableQuery,
            headers = localVariableHeaders
        )
        val response = request<Event>(
            localVariableConfig,
            localVariableBody
        )

        return when (response.responseType) {
            ResponseType.Success -> (response as Success<*>).data as Event
            ResponseType.Informational -> TODO()
            ResponseType.Redirection -> TODO()
            ResponseType.ClientError -> throw ClientException((response as ClientError<*>).body as? String ?: "Client error")
            ResponseType.ServerError -> throw ServerException((response as ServerError<*>).message ?: "Server error")
            else -> throw kotlin.IllegalStateException("Undefined ResponseType.")
        }
    }

}
