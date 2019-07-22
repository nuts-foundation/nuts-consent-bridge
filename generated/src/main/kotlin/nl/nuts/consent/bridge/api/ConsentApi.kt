package nl.nuts.consent.bridge.api

import nl.nuts.consent.bridge.model.ConsentRequestJobState
import nl.nuts.consent.bridge.model.ConsentRequestState
import nl.nuts.consent.bridge.model.NewConsentRequestState
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.validation.annotation.Validated
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.multipart.MultipartFile
import org.springframework.beans.factory.annotation.Autowired

import javax.validation.Valid
import javax.validation.constraints.*

import kotlin.collections.List
import kotlin.collections.Map

@Controller
@Validated
@RequestMapping("\${api.base-path:}")
class ConsentApiController(@Autowired(required = true) val service: ConsentApiService) {


    @RequestMapping(
            value = ["/api/consent/consent_request/{uuid}/accept"],
            produces = ["application/json", "text/plain"], 
            consumes = ["application/json"],
            method = [RequestMethod.POST])
    fun acceptConsentRequestState( @PathVariable("uuid") uuid: String, @Valid @RequestBody partyAttachmentSignature: PartyAttachmentSignature): ResponseEntity<ConsentRequestJobState> {
        return ResponseEntity(service.acceptConsentRequestState(uuid, partyAttachmentSignature), HttpStatus.OK)
    }


    @RequestMapping(
            value = ["/api/consent/consent_request/{uuid}/finalize"],
            produces = ["application/json", "text/plain"], 
            method = [RequestMethod.POST])
    fun finalizeConsentRequestState( @PathVariable("uuid") uuid: String): ResponseEntity<ConsentRequestJobState> {
        return ResponseEntity(service.finalizeConsentRequestState(uuid), HttpStatus.OK)
    }


    @RequestMapping(
            value = ["/api/attachment/{secureHash}"],
            produces = ["application/octet-stream"], 
            method = [RequestMethod.GET])
    fun getAttachmentBySecureHash( @PathVariable("secureHash") secureHash: String): ResponseEntity<ByteArray> {
        return ResponseEntity(service.getAttachmentBySecureHash(secureHash), HttpStatus.OK)
    }


    @RequestMapping(
            value = ["/api/consent_request_state/{uuid}"],
            produces = ["application/json"], 
            method = [RequestMethod.GET])
    fun getConsentRequestStateById( @PathVariable("uuid") uuid: String): ResponseEntity<ConsentRequestState> {
        return ResponseEntity(service.getConsentRequestStateById(uuid), HttpStatus.OK)
    }


    @RequestMapping(
            value = ["/api/consent/consent_request"],
            produces = ["application/json", "text/plain"], 
            consumes = ["application/json"],
            method = [RequestMethod.POST])
    fun newConsentRequestState( @Valid @RequestBody newConsentRequestState: NewConsentRequestState): ResponseEntity<ConsentRequestJobState> {
        return ResponseEntity(service.newConsentRequestState(newConsentRequestState), HttpStatus.OK)
    }
}
