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

package nl.nuts.consent.bridge.listener

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.readFully
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*
import nl.nuts.consent.bridge.api.ConsentApiServiceImpl
import nl.nuts.consent.bridge.model.Metadata
import nl.nuts.consent.bridge.model.NewConsentRequestState
import nl.nuts.consent.bridge.nats.Event
import nl.nuts.consent.bridge.nats.NutsEventPublisher
import nl.nuts.consent.bridge.rpc.CordaRPClientFactory
import nl.nuts.consent.bridge.rpc.CordaRPClientWrapper
import nl.nuts.consent.model.ConsentMetadata
import nl.nuts.consent.state.ConsentRequestState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.convert.ConversionService
import org.springframework.stereotype.Service
import rx.Subscription
import java.nio.charset.Charset
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Generic class for registering callbacks for State changes in the Vault.
 * The idea is that one instance is used per client per State. If multiple instances are used for the same state, duplicate events will be published.
 * Each instance uses a single Corda RPC connection. These are limited, so starting up instances from threads might not be the wisest.
 */
class CordaStateChangeListener<S : ContractState>(
        val cordaRPClientWrapper: CordaRPClientWrapper,
        val epochOffset:Long = 0,
        val producedCallback:Callback<S> = Callbacks::noOpCallback,
        val consumedCallback:Callback<S> = Callbacks::noOpCallback) {

    val logger:Logger = LoggerFactory.getLogger(this::class.java)

    private var shutdown:Boolean = false

    /**
     * This will initiate the RPC connection and start the observer stream
     */
    fun start(stateClass: Class<S>) {
        if (shutdown) {
            return
        }

        val proxy = cordaRPClientWrapper.proxy()

        if (proxy == null) {
            logger.warn("Couldn't get proxy, stopping CordaStateChangeListener")
            shutdown = true
            return
        }

        // time criteria
        val asOfDateTime = Instant.ofEpochSecond(epochOffset, 0)
        val recordedAfterExpression = QueryCriteria.TimeCondition(
                QueryCriteria.TimeInstantType.RECORDED,
                ColumnPredicate.BinaryComparison(BinaryComparisonOperator.GREATER_THAN_OR_EQUAL, asOfDateTime))
        val recordedCriteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL, timeCondition = recordedAfterExpression)

        val consumedAfterExpression = QueryCriteria.TimeCondition(
                QueryCriteria.TimeInstantType.CONSUMED,
                ColumnPredicate.BinaryComparison(BinaryComparisonOperator.GREATER_THAN_OR_EQUAL, asOfDateTime))
        val consumedCriteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL, timeCondition = consumedAfterExpression)

        // feed criteria
        val feed = proxy.vaultTrackBy(
                recordedCriteria.or(consumedCriteria),
                PageSpecification(DEFAULT_PAGE_NUM, 100),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.CommonStateAttribute.STATE_REF), Sort.Direction.ASC))),
                stateClass)

        val observable = feed.updates

        // thread safe storage
        val retryableStateUpdatesSubscription: AtomicReference<Subscription?> = AtomicReference(null)

        // transfer feed events to callback
        val subscription = observable.subscribe( { update ->
            logger.debug("Observed ${update.type} state update")

            update.produced.forEach {
                logger.debug("Observed produced state ${it.state.javaClass.name} within contract ${it.state.contract}")

                producedCallback.invoke(it)
            }
            update.consumed.forEach {
                logger.debug("Observed consumed state ${it.state.javaClass.name} within contract ${it.state.contract}")

                consumedCallback.invoke(it)
            }
        },
        { e:Throwable ->
            logger.error(e.message)
            logger.info("Unsubscribing and disconnecting...")

            // cleanup stuff to make sure we don't leak anything
            retryableStateUpdatesSubscription.get()?.unsubscribe()
            cordaRPClientWrapper.close()

            // start again
            start(stateClass)
        })

        // store in atomic reference, so that if the callback errors, the other thread can operate on it safely
        retryableStateUpdatesSubscription.set(subscription)
    }
    /**
     * Closes the RPC connection to the Corda node
     */
    fun stop() {
        shutdown = true
    }
}

/**
 * Not a real Spring factory but needed to disconnect the NutsEventListener from the CordaStateChangeListener
 * This way it's easier to test everything. It also starts the listener in case the app is started.
 */
@Service
class CordaStateChangeListenerController {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    lateinit var cordaStateChangeListener: CordaStateChangeListener<ConsentRequestState>

    @Autowired
    lateinit var cordaRPClientFactory: CordaRPClientFactory
    lateinit var cordaRPClientWrapper: CordaRPClientWrapper

    @Autowired
    lateinit var nutsEventPublisher: NutsEventPublisher

    @Qualifier("mvcConversionService")
    @Autowired
    lateinit var conversionService: ConversionService

    @PostConstruct
    fun init() {
        cordaRPClientWrapper = cordaRPClientFactory.getObject()
        cordaStateChangeListener = CordaStateChangeListener(cordaRPClientWrapper, 0, {
            logger.debug("Received produced state event from Corda: ${it.state.data}")

            // find corresponding event in Nuts event store, if not found create a new state with state == 'to be accepted'
            // the contents of the new event will be a NewConsentRequestState object as json/base64

            val state = it.state.data
            val event = contractStateToEvent(state)
            val jsonBytes = ConsentApiServiceImpl.Serialisation.objectMapper().writeValueAsBytes(event)
            nutsEventPublisher.publish("consentRequest", jsonBytes)
        })

        cordaStateChangeListener.start(ConsentRequestState::class.java)
    }

    @PreDestroy
    fun destroy() {
        logger.debug("Stopping corda state change listener")

        cordaStateChangeListener.stop()
        cordaRPClientWrapper.term()

        logger.info("Corda state change listener stopped")
    }


    private fun contractStateToEvent(state: ConsentRequestState) : Event {

        val attachment= getAttachment(state.attachments.first())


        val ncrs =  NewConsentRequestState(
                externalId = state.consentStateUUID.externalId!!,
                metadata = conversionService.convert(attachment.metadata, Metadata::class.java)!!,
                attachment = Base64.getEncoder().encodeToString(attachment.data)
        )

        val ncrsBytes = ConsentApiServiceImpl.Serialisation.objectMapper().writeValueAsBytes(ncrs)
        val ncrsBase64 = Base64.getEncoder().encodeToString(ncrsBytes)

        return Event(
                UUID = UUID.randomUUID().toString(),
                state = "to be accepted",
                retryCount = 0,
                externalId = state.consentStateUUID.externalId!!,
                consentId = state.consentStateUUID.id.toString(),
                custodian = "unknown",
                payload = ncrsBase64
        )
    }

    private fun getAttachment(secureHash: SecureHash) : Attachment {
        val jarInputStream = JarInputStream(cordaRPClientWrapper.proxy()?.openAttachment(secureHash))

        var metadata:ConsentMetadata? = null
        var attachment:ByteArray? = null

        do {
            var entry: JarEntry = jarInputStream.nextJarEntry

            if (entry.name.endsWith(".json")) {
                val reader = jarInputStream.bufferedReader(Charset.forName("UTF8"))
                metadata = ConsentApiServiceImpl.Serialisation.objectMapper().readValue(reader, ConsentMetadata::class.java)
            } else if (entry.name.endsWith(".bin")) {
                attachment = jarInputStream.readFully()
            }
        } while (jarInputStream.available() != 0)

        return Attachment(metadata!!, attachment!!)
    }
}

data class Attachment (
        val metadata: ConsentMetadata,
        val data: ByteArray
)

typealias Callback<S> = (StateAndRef<S>) -> Unit
object Callbacks {
    fun <S : ContractState> noOpCallback(state:StateAndRef<S>) {

    }
}