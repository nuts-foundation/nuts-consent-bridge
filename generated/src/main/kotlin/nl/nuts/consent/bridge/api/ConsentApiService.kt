package nl.nuts.consent.bridge.api

import nl.nuts.consent.bridge.model.ConsentRequestState

interface ConsentApiService {

    fun getAttachmentBySecureHash(secureHash: String): ByteArray

    fun getConsentRequestStateById(uuid: String): ConsentRequestState
}
