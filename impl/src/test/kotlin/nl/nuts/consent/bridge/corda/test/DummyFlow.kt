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

package nl.nuts.consent.bridge.corda.test

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.*
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.lang.IllegalStateException

/**
 * This is a one-sided flow. No counter parties are needed, BUT it still requires a Notary
 */
object DummyFlow {
    @InitiatingFlow
    @StartableByRPC
    class ProduceFlow : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            val me = serviceHub.myInfo.legalIdentities.first()
            val state = DummyState(listOf(me))

            val txCommand = Command(DummyContract.DummyCommand(), state.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(state)
                    .addCommand(txCommand)

            txBuilder.verify(serviceHub)
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            serviceHub.recordTransactions(partSignedTx)

            return partSignedTx
        }

    }

    @InitiatingFlow
    @StartableByRPC
    class ConsumeFlow(val state:StateAndRef<DummyState>) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            val me = serviceHub.myInfo.legalIdentities.first()

            val results = builder {
                val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                val refCriteria = QueryCriteria.VaultQueryCriteria().withStateRefs(listOf(state.ref))
                serviceHub.vaultService.queryBy(DummyState::class.java, generalCriteria.and(refCriteria))
            }

            val txCommand = Command(DummyContract.DummyCommand(), listOf(me.owningKey))
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(results.states.first())
                    .addCommand(txCommand)

            txBuilder.verify(serviceHub)
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            serviceHub.recordTransactions(partSignedTx)

            return partSignedTx
        }

    }

    @InitiatingFlow
    @StartableByRPC
    class ErrorFlow : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            throw IllegalStateException("error")
        }
    }
}