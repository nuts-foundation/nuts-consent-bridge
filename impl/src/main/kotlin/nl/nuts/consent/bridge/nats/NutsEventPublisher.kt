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

package nl.nuts.consent.bridge.nats

import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.*
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class NutsEventPublisher {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    lateinit var consentBridgeNatsProperties: ConsentBridgeNatsProperties

    lateinit var cf: StreamingConnectionFactory

    lateinit var connection: StreamingConnection

    @PostConstruct
    fun init() {
        cf = StreamingConnectionFactory(consentBridgeNatsProperties.cluster, "nutsEventPublisher-${Integer.toHexString(Random().nextInt())}")
        cf.natsUrl = consentBridgeNatsProperties.address

        connection = cf.createConnection()
    }

    // todo fatal errors
    fun publish(subject:String, data: ByteArray) {
        connection.publish(subject, data)
    }

    @PreDestroy
    fun destroy() {
        logger.debug("Stopping publisher")

        connection.close()

        logger.info("Publisher stopped")
    }
}