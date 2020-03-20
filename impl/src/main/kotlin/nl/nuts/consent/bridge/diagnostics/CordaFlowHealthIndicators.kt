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

package nl.nuts.consent.bridge.diagnostics

import net.corda.core.messaging.startFlow
import nl.nuts.consent.bridge.ConsentRegistryProperties
import nl.nuts.consent.bridge.SchedulerProperties
import nl.nuts.consent.bridge.corda.CordaManagedConnection
import nl.nuts.consent.bridge.corda.CordaManagedConnectionFactory
import nl.nuts.consent.bridge.corda.CordaService
import nl.nuts.consent.bridge.corda.TIMEOUT_ERROR
import nl.nuts.consent.flow.DiagnosticFlows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy
import kotlin.math.floor


/**
 * Shared class for logic calling Corda Flows
 */
abstract class FlowHealthIndicator : HealthIndicator {
    var lastCheck : PingResult = PingResult(true)

    @Autowired
    protected lateinit var schedulerProperties: SchedulerProperties

    @Autowired
    lateinit var cordaManagedConnectionFactory: CordaManagedConnectionFactory

    protected lateinit var cordaManagedConnection: CordaManagedConnection

    /**
     * Init corda managed connection
     */
    @PostConstruct
    fun init() {
        cordaManagedConnection = cordaManagedConnectionFactory.`object`
        cordaManagedConnection.name = "health"
        cordaManagedConnection.connect()
    }

    /**
     * Terminate corda connection before destroying bean
     */
    @PreDestroy
    fun destroy() {
        cordaManagedConnection?.terminate()
    }

    override fun health(): Health {
        var now = System.currentTimeMillis()
        if (!lastCheck.success) {
            return Health.down().withDetail("reason", lastCheck.error).build()
        }

        if (now - lastCheck.timestamp > 2 * schedulerProperties.delay) {
            val age = floor((now - lastCheck.timestamp)/60000.0)
            return Health.down().withDetail("reason", "latest successful check was ${age.toInt()} minutes ago").build()
        }

        return Health.up().build()
    }
}

/**
 * Interface for exposing the Async scheduled method
 */
interface CordaHealthIndicator {
    /**
     * Scheduled function calling the actual Corda flow. Runs async which allows for a larger timeout.
     */
    fun doFlowHealthCheck() : PingResult;
}

/**
 * Scheduled component that tries to run the NotaryPing flow via Corda RPC and stores the latest successful ping.
 * When this ping is less than X then health is OK.
 */
@EnableAsync
@Component
class CordaNotaryPingHealthIndicator : CordaHealthIndicator, FlowHealthIndicator() {

    @Async
    @Scheduled(fixedDelayString = "#{schedulerProperties.delay}", initialDelayString = "#{schedulerProperties.initialDelay}")
    override fun doFlowHealthCheck() : PingResult {
        lastCheck = pingNotary()
        return lastCheck
    }

    /**
     * Start the PingNotaryFlow
     * Expects answer within 10 seconds
     *
     * @return PingResult with success as true/false and an error description when false
     */
    fun pingNotary() : PingResult {
        try {
            val f = cordaManagedConnection.proxy().startFlow(DiagnosticFlows::PingNotaryFlow)

            f.returnValue.get(10, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            return PingResult(false, TIMEOUT_ERROR)
        }
        return PingResult(true)
    }
}

/**
 * Scheduled component that tries to run the NotaryPing flow via Corda RPC and stores the latest successful ping.
 * When this ping is less than X then health is OK.
 */
@EnableAsync
@Component
class CordaRandomPingHealthIndicator : CordaHealthIndicator, FlowHealthIndicator() {

    @Async
    @Scheduled(fixedDelayString = "#{schedulerProperties.delay}", initialDelayString = "#{schedulerProperties.initialDelay}")
    override fun doFlowHealthCheck() : PingResult {
        lastCheck = pingRandom()
        return lastCheck
    }

    /**
     * Start the PingRandomFlow
     * Expects answer within 10 seconds
     *
     * @return PingResult with success as true/false and an error description when false
     */
    fun pingRandom() : PingResult {
        try {
            val f = cordaManagedConnection.proxy().startFlow(DiagnosticFlows::PingRandomFlow)

            f.returnValue.get(10, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            return PingResult(false, TIMEOUT_ERROR)
        }
        return PingResult(true)
    }
}

/**
 * Helper for storing result of ping action
 */
data class PingResult (
    val success: Boolean,
    val error: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Simple check to know if a Corda RPC Connection can be established
 */
@Component
class CordaConnectionHealthIndicator : HealthIndicator {

    @Autowired
    lateinit var cordaManagedConnectionFactory: CordaManagedConnectionFactory

    override fun health(): Health {
        try {
            cordaManagedConnectionFactory.getObject().getConnection()?.close() // throws exc
            return Health.up().build()
        } catch (e: IllegalStateException) {
            return Health.down(e).build()
        }
    }
}