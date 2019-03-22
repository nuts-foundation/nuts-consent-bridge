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

/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package nl.nuts.consent.bridge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties("nuts.consent.rpc")
data class CordaRPCProperties(var host: String = "localhost", var port:Int = 10009, var user:String = "user1", var password:String = "test", var retryIntervalSeconds:Int = 5)

@SpringBootApplication
@EnableConfigurationProperties(CordaRPCProperties::class)
class NutsConsentBridge

fun main(args: Array<String>) {
    runApplication<NutsConsentBridge>(*args)
}
