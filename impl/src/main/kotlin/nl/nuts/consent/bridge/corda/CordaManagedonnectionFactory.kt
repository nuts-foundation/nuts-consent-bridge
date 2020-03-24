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

package nl.nuts.consent.bridge.corda

import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.AbstractFactoryBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * abstraction for creating Corda RPC connection,
 */
class CordaManagedConnectionFactory : AbstractFactoryBean<CordaManagedConnection>() {
    @Autowired
    lateinit var consentBridgeRPCProperties: ConsentBridgeRPCProperties

    override fun createInstance(): CordaManagedConnection {
        return CordaManagedConnection(consentBridgeRPCProperties)
    }

    override fun getObjectType(): Class<*>? {
        return CordaManagedConnection::class.java
    }

    override fun isSingleton(): Boolean {
        return false
    }

    override fun destroyInstance(instance: CordaManagedConnection?) {
        instance?.terminate()
    }
}

/**
 * Spring configuration for registering/creating CordaRPClientFactory and CordaRPClientWrapper beans.
 */
@Configuration
class CordaRPCConnectionConfiguration {

    /**
     * Create a new CordaManagedConnectionFactory
     */
    @Bean
    fun cordaManagedConnectionFactory() : CordaManagedConnectionFactory {
        return CordaManagedConnectionFactory()
    }
}
