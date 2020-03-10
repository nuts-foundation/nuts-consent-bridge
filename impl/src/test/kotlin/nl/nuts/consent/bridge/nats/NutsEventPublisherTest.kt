/*
 * Nuts consent bridge
 * Copyright (C) 2020 Nuts community
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

import com.nhaarman.mockito_kotlin.mock
import io.nats.client.ConnectionListener
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.streaming.Message
import io.nats.streaming.StreamingConnection
import io.nats.streaming.StreamingConnectionFactory
import io.nats.streaming.SubscriptionOptions
import nl.nuts.consent.bridge.ConsentBridgeNatsProperties
import nl.nuts.consent.bridge.rpc.CordaService
import np.com.madanpokharel.embed.nats.EmbeddedNatsConfig
import np.com.madanpokharel.embed.nats.EmbeddedNatsServer
import np.com.madanpokharel.embed.nats.NatsServerConfig
import np.com.madanpokharel.embed.nats.NatsStreamingVersion
import np.com.madanpokharel.embed.nats.ServerType
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.ServerSocket
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull

class NutsEventPublisherTest {

    lateinit var cf : StreamingConnectionFactory
    lateinit var connection: StreamingConnection
    lateinit var nutsEventPublisher: NutsEventPublisher

    companion object {
        var natsServer: EmbeddedNatsServer? = null

        @BeforeClass
        @JvmStatic fun setupClass() {
            // server
            var port = 4222
            ServerSocket(0).use { port = it.localPort }
            val config = EmbeddedNatsConfig.Builder()
                .withNatsServerConfig(
                    NatsServerConfig.Builder()
                        .withServerType(ServerType.NATS_STREAMING)
                        .withPort(port)
                        .withNatsStreamingVersion(NatsStreamingVersion.V0_16_2)
                        .build()
                )
                .build()
            natsServer = EmbeddedNatsServer(config)
            natsServer?.startServer()
        }

        @AfterClass
        @JvmStatic fun tearDownClass() {
            natsServer?.stopServer()
        }
    }

    @Before
    fun setup() {
        cf = StreamingConnectionFactory("test-cluster", "cordaBridgeTest-${Integer.toHexString(Random().nextInt())}")

        nutsEventPublisher = initNewPublisher()
        cf.natsUrl = natsServer?.natsUrl

        val l = CountDownLatch(1)

        // client connection listener
        val listener = ConnectionListener { conn, type ->
            when(type) {
                ConnectionListener.Events.RECONNECTED,
                ConnectionListener.Events.CONNECTED -> {
                    // notify
                    cf.natsConnection = conn
                    connection = cf.createConnection()
                    l.countDown()
                }
            }
        }

        // client
        val o = Options.Builder()
            .server(natsServer?.natsUrl)
            .maxReconnects(-1)
            .connectionListener(listener)
            .build()
        Nats.connectAsynchronously(o, false)

        l.await(10, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        connection.close()
        nutsEventPublisher.destroy()
        nutsEventPublisher.destroyBase()
    }

    private fun initNewPublisher() : NutsEventPublisher {
        val nutsEventPublisher = NutsEventPublisher()
        nutsEventPublisher.consentBridgeNatsProperties = ConsentBridgeNatsProperties(address = natsServer!!.natsUrl)
        nutsEventPublisher.init()

        return nutsEventPublisher
    }

    @Test
    fun `events are published to the retry queue`() {
        val ref = AtomicReference<Message>()
        val subscription = connection?.subscribe("$NATS_CONSENT_RETRY_SUBJECT-1", {
            ref.set(it)

        }, SubscriptionOptions.Builder().build())

        nutsEventPublisher.publishToRetry(1, "test".toByteArray())

        Thread.sleep(1000)

        assertNotNull(ref.get())

        subscription.close()
    }
}