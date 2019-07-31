package nl.nuts.consent.bridge.api

import nl.nuts.consent.bridge.model.FullConsentRequestState

interface ConsentApiService {

    fun getAttachmentBySecureHash(secureHash: String): ByteArray

    fun getConsentRequestStateById(uuid: String): FullConsentRequestState
}
