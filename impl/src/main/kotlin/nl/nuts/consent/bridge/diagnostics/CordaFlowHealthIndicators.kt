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

import nl.nuts.consent.bridge.SchedulerProperties
import nl.nuts.consent.bridge.rpc.CordaService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlin.math.floor


/**
 * Shared class for logic calling Corda Flows
 */
abstract class FlowHealthIndicator : HealthIndicator {
    var lastCheck : CordaService.PingResult = CordaService.PingResult(true)

    @Autowired
    protected lateinit var schedulerProperties: SchedulerProperties

    @Autowired
    protected lateinit var cordaService: CordaService

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
    fun doFlowHealthCheck() : CordaService.PingResult;
}

/**
 * Scheduled component that tries to run the NotaryPing flow via Corda RPC and stores the latest successful ping.
 * When this ping is less than X then health is OK.
 */
@EnableAsync
@Component
class CordaNotaryHealthIndicator : CordaHealthIndicator, FlowHealthIndicator() {

    @Async
    @Scheduled(fixedDelayString = "#{schedulerProperties.delay}", initialDelayString = "#{schedulerProperties.initialDelay}")
    override fun doFlowHealthCheck() : CordaService.PingResult {
        lastCheck = cordaService.pingNotary()
        return lastCheck
    }
}

/**
 * Scheduled component that tries to run the NotaryPing flow via Corda RPC and stores the latest successful ping.
 * When this ping is less than X then health is OK.
 */
@EnableAsync
@Component
class CordaRandomHealthIndicator : CordaHealthIndicator, FlowHealthIndicator() {

    @Async
    @Scheduled(fixedDelayString = "#{schedulerProperties.delay}", initialDelayString = "#{schedulerProperties.initialDelay}")
    override fun doFlowHealthCheck() : CordaService.PingResult {
        lastCheck = cordaService.pingRandom()
        return lastCheck
    }
}