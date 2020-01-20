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

package nl.nuts.consent.bridge

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Configuration data class for Corda RPC settings
 */
@Configuration
@ConfigurationProperties("nuts.consent.rpc")
data class ConsentBridgeRPCProperties(
        var host: String = "localhost",
        var port:Int = 7887,
        var user:String = "admin",
        var password:String = "nuts",
        var retryIntervalSeconds:Int = 5,
        var retryCount:Int = -1,
        var enabled:Boolean = false)

/**
 * Configuration data class for Nats settings
 */
@Configuration
@ConfigurationProperties("nuts.consent.nats")
data class ConsentBridgeNatsProperties(
        var address:String = "nats://localhost:4222",
        var cluster:String = "test-cluster")

/**
 * Configuration data class for registry API settings
 */
@Configuration
@ConfigurationProperties("nuts.consent.registry")
data class ConsentRegistryProperties(
        var url: String = "http://localhost:8088")

/**
 * Configuration data class for event store API settings
 */
@Configuration
@ConfigurationProperties("nuts.consent.events")
data class EventStoreProperties(
        var url: String = "http://localhost:8088")

@Configuration
@EnableScheduling
class GeneralConfiguration

/**
 * Configuration data class for scheduler
 */
@Configuration
@ConfigurationProperties("nuts.consent.schedule")
data class SchedulerProperties(
        var delay: Long = 15 * 60 * 1000,
        var initialDelay: Long =  1000
)