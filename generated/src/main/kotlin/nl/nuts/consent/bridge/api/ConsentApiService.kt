package nl.nuts.consent.bridge.api

import nl.nuts.consent.bridge.model.ConsentRequestMetadata
import nl.nuts.consent.bridge.model.ConsentRequestState
import nl.nuts.consent.bridge.model.EventStreamSetting
import nl.nuts.consent.bridge.model.PartyAttachmentSignature

interface ConsentApiService {

    fun acceptConsentRequestState(uuid: String,partyAttachmentSignature: PartyAttachmentSignature): String

    fun finalizeConsentRequestState(uuid: String): String

    fun getAttachmentBySecureHash(secureHash: String): ByteArray

    fun getConsentRequestStateById(uuid: String): ConsentRequestState

    fun initEventStream(eventStreamSetting: EventStreamSetting): String

    fun newConsentRequestState(consentRequestMetadata: ConsentRequestMetadata,attachment: org.springframework.web.multipart.MultipartFile): String
}
