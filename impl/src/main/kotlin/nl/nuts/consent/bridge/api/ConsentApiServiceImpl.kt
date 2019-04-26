/*
 * Nuts consent bridge
 * Copyright (C) 2019 Nuts community
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package nl.nuts.consent.bridge.api

import nl.nuts.consent.bridge.model.AcceptConsentRequestState
import nl.nuts.consent.bridge.model.ConsentRequestState
import nl.nuts.consent.bridge.model.EventStreamSetting
import nl.nuts.consent.bridge.zmq.Publisher
import nl.nuts.consent.bridge.zmq.Subscription
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.File

/**
 * Concrete implementation of the ConsentApiService. This class connects our custom logic to the generated API's
 */
@Service
class ConsentApiServiceImpl : ConsentApiService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var publisher: Publisher

    override fun acceptConsentRequestState(acceptConsentRequestState: AcceptConsentRequestState?) : String {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getAttachmentBySecureHash(secureHash: String): File {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getConsentRequestStateById(linearId: String): ConsentRequestState {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun initEventStream(eventStreamSetting: EventStreamSetting) : String {
        publisher.addSubscription(Subscription(eventStreamSetting.topic, eventStreamSetting.epoch))
        return "OK"
    }
}