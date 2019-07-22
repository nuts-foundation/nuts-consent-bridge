package nl.nuts.consent.bridge.api

import nl.nuts.consent.bridge.model.ConsentRequestJobState
import nl.nuts.consent.bridge.model.ConsentRequestState
import nl.nuts.consent.bridge.model.NewConsentRequestState
import nl.nuts.consent.bridge.model.PartyAttachmentSignature

interface ConsentApiService {

    fun acceptConsentRequestState(uuid: String,partyAttachmentSignature: PartyAttachmentSignature): ConsentRequestJobState

    fun finalizeConsentRequestState(uuid: String): ConsentRequestJobState

    fun getAttachmentBySecureHash(secureHash: String): ByteArray

    fun getConsentRequestStateById(uuid: String): ConsentRequestState

    fun newConsentRequestState(newConsentRequestState: NewConsentRequestState): ConsentRequestJobState
}
