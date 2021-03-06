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
package nl.nuts.consent.bridge.events.apis

import nl.nuts.consent.bridge.events.models.Event
import nl.nuts.consent.bridge.events.models.EventListResponse

import nl.nuts.consent.bridge.events.infrastructure.*

class EventApi(basePath: kotlin.String = "http://localhost") : ApiClient(basePath) {

    /**
    * Find a specific event
    * 
    * @param uuid uuid of consent request action, generated by first event 
    * @return Event
    */
    @Suppress("UNCHECKED_CAST")
    fun getEvent(uuid: java.util.UUID) : Event {
        val localVariableBody: kotlin.Any? = null
        val localVariableQuery: MultiValueMap = mapOf()
        val localVariableHeaders: kotlin.collections.Map<kotlin.String,kotlin.String> = mapOf()
        val localVariableConfig = RequestConfig(
            RequestMethod.GET,
            "/events/{uuid}".replace("{"+"uuid"+"}", "$uuid"),
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

    /**
    * Find a specific event by its externalId
    * 
    * @param externalId external_id of consent request action 
    * @return Event
    */
    @Suppress("UNCHECKED_CAST")
    fun getEventByExternalId(externalId: kotlin.String) : Event {
        val localVariableBody: kotlin.Any? = null
        val localVariableQuery: MultiValueMap = mapOf()
        val localVariableHeaders: kotlin.collections.Map<kotlin.String,kotlin.String> = mapOf()
        val localVariableConfig = RequestConfig(
            RequestMethod.GET,
            "/events/by_external_id/{external_id}".replace("{"+"external_id"+"}", "$externalId"),
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

    /**
    * Return all events currently in store
    * 
    * @return EventListResponse
    */
    @Suppress("UNCHECKED_CAST")
    fun list() : EventListResponse {
        val localVariableBody: kotlin.Any? = null
        val localVariableQuery: MultiValueMap = mapOf()
        val localVariableHeaders: kotlin.collections.Map<kotlin.String,kotlin.String> = mapOf()
        val localVariableConfig = RequestConfig(
            RequestMethod.GET,
            "/events",
            query = localVariableQuery,
            headers = localVariableHeaders
        )
        val response = request<EventListResponse>(
            localVariableConfig,
            localVariableBody
        )

        return when (response.responseType) {
            ResponseType.Success -> (response as Success<*>).data as EventListResponse
            ResponseType.Informational -> TODO()
            ResponseType.Redirection -> TODO()
            ResponseType.ClientError -> throw ClientException((response as ClientError<*>).body as? String ?: "Client error")
            ResponseType.ServerError -> throw ServerException((response as ServerError<*>).message ?: "Server error")
            else -> throw kotlin.IllegalStateException("Undefined ResponseType.")
        }
    }

}
