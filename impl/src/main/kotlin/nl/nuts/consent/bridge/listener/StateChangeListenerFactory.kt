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
import nl.nuts.consent.bridge.ConsentBridgeRPCProperties
import nl.nuts.consent.bridge.rpc.CordaRPClientFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * Not a real Spring factory but needed to disconnect the NutsEventListener from the StateChangeListener
 * This way it's easier to test everything.
 */
@Service
class StateChangeListenerFactory {

    @Autowired
    lateinit var cordaRPClientFactory: CordaRPClientFactory

    fun <T : ContractState> createInstance(epoch:Long) : StateChangeListener<T> {
        return StateChangeListener(cordaRPClientFactory.getObject(), epoch)
    }

    fun <T : ContractState> createInstance() : StateChangeListener<T> {
        return StateChangeListener(cordaRPClientFactory.getObject())
    }
}