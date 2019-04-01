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

package nl.nuts.consent.bridge.zmq

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.identity.Party
import net.corda.core.messaging.startFlow
import net.corda.node.internal.NodeWithInfo
import net.corda.node.services.Permissions
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.node.User
import net.corda.testing.node.internal.NodeBasedTest
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.ConsentBridgeZMQProperties
import nl.nuts.consent.flow.ConsentRequestFlows
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit4.rules.SpringClassRule
import org.springframework.test.context.junit4.rules.SpringMethodRule
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

const val VALID_META_ZIP_PATH = "src/test/resources/valid_metadata.zip"

@SpringBootTest
@ActiveProfiles("test")
// needed since the SerializationContext for Corda is shared with the VM and Corda shutsdown everything between tests
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ZMQIntegrationTest  : NodeBasedTest(listOf("nl.nuts.consent"), notaries = listOf(DUMMY_NOTARY_NAME)) {
    private val validAttachment = File(VALID_META_ZIP_PATH)

    companion object {
        val PASSWORD = "test"
        val USER = "user1"
        val rpcUser = User(USER, PASSWORD, permissions = setOf(Permissions.all()))
    }

    // we use the Spring rules and not the runner in order to not interfere with the Corda rules
    // with the springRunner the initialisation order of the serialisation context is wrong
    @Rule
    @JvmField
    final val springRule = SpringClassRule()

    @Rule
    @JvmField
    final val springMethodRule = SpringMethodRule()

    @Autowired
    lateinit var consentBridgeZMQProperties: ConsentBridgeZMQProperties

    @Autowired
    lateinit var consentBridgeRPCProperties: ConsentBridgeRPCProperties

    @Autowired
    lateinit var context: ZContext

    @Autowired
    lateinit var publisher: Publisher

    private lateinit var aliceNode: NodeWithInfo
    private lateinit var bobNode: NodeWithInfo
    private lateinit var identity: Party

    private lateinit var client: CordaRPCClient
    private var connection: CordaRPCConnection? = null

    @Before
    override fun setUp() {
        super.setUp()
        aliceNode = startNode(ALICE_NAME, rpcUsers = listOf(rpcUser), configOverrides = mapOf("rpcSettings.address" to "localhost:${consentBridgeRPCProperties.port}"))
        bobNode = startNode(BOB_NAME, rpcUsers = listOf(rpcUser))
        identity = notaryNodes.first().info.identityFromX500Name(DUMMY_NOTARY_NAME)

        // different RPC client for starting flow
        client = CordaRPCClient(aliceNode.node.configuration.rpcOptions.address, CordaRPCClientConfiguration.DEFAULT.copy(maxReconnectAttempts = 1))
        connection = client.start(USER, PASSWORD, null, null)

    }

    @After
    fun done() {
        publisher.destroy()
        connection?.close()
    }

    @Test
    fun `Started Corda flow results in produced state event`() {
        val subSocket = context.createSocket(SocketType.SUB)
        subSocket.connect(consentBridgeZMQProperties.publisherAddress)
        subSocket.subscribe("topic")

        context.createSocket(SocketType.REQ).use {
            it.connect("tcp://localhost:${consentBridgeZMQProperties.routerPort}")
            it.sendMore("topic") // the topic we wish to receive events on
            it.send("0") // the epoch from which we'd like to receive events
            assertEquals("ACK", it.recvStr()) // part of protocol
        }

        // upload attachments
        val secureHash = connection!!.proxy.uploadAttachment(validAttachment.inputStream())

        // start a NewConsentRequest flow
        connection!!.proxy.startFlow(
                ConsentRequestFlows::NewConsentRequest,
                "uuid",
                setOf(secureHash),
                listOf(bobNode.info.identityFromX500Name(BOB_NAME))
        ).returnValue.get()

        val data = subSocket.recvStr()

        context.destroySocket(subSocket)

        assertEquals("topic:ConsentRequestState:uuid_REQ:produced", data)
    }

    @Test
    fun `Started Corda flow results in produced state event for each client`() {
        val sockets = ArrayList<ZMQ.Socket>()
        val events = ArrayList<String>()

        repeat(4) {i->
            val subSocket = context.createSocket(SocketType.SUB)
            subSocket.linger = 0
            subSocket.connect(consentBridgeZMQProperties.publisherAddress)
            subSocket.subscribe("topic-$i")

            sockets.add(subSocket)

            context.createSocket(SocketType.REQ).use {
                it.connect("tcp://localhost:${consentBridgeZMQProperties.routerPort}")
                it.sendMore("topic-$i") // the topic we wish to receive events on
                it.send("0") // the epoch from which we'd like to receive events
                assertEquals("ACK", it.recvStr()) // part of protocol
            }
        }

        // upload attachments
        val secureHash = connection!!.proxy.uploadAttachment(validAttachment.inputStream())

        // start a NewConsentRequest flow
        connection!!.proxy.startFlow(
                ConsentRequestFlows::NewConsentRequest,
                "uuid",
                setOf(secureHash),
                listOf(bobNode.info.identityFromX500Name(BOB_NAME))
        ).returnValue.get()

        sockets.forEach {
            events.add(it.recvStr())
        }

        repeat(4) {
            assertTrue(events.contains("topic-$it:ConsentRequestState:uuid_REQ:produced"))
        }
    }
}