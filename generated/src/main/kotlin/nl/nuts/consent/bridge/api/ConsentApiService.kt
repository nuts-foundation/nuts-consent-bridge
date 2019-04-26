package nl.nuts.consent.bridge.api

import nl.nuts.consent.bridge.model.AcceptConsentRequestState
import nl.nuts.consent.bridge.model.ConsentRequestState
import nl.nuts.consent.bridge.model.EventStreamSetting

interface ConsentApiService {

    fun acceptConsentRequestState(acceptConsentRequestState: AcceptConsentRequestState?): String

    fun getAttachmentBySecureHash(secureHash: String): java.io.File

    fun getConsentRequestStateById(linearId: String): ConsentRequestState

    fun initEventStream(eventStreamSetting: EventStreamSetting): String
}
