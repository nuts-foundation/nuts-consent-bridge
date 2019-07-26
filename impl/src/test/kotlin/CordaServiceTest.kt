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

package nl.nuts.consent.bridge.rpc

import com.nhaarman.mockito_kotlin.*
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.TransactionState
import net.corda.core.crypto.SecureHash
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.services.Vault
import nl.nuts.consent.bridge.Serialization
import nl.nuts.consent.bridge.api.NotFoundException
import nl.nuts.consent.model.ConsentMetadata
import nl.nuts.consent.model.Domain
import nl.nuts.consent.model.Period
import nl.nuts.consent.model.SymmetricKey
import nl.nuts.consent.state.ConsentRequestState
import org.assertj.core.api.Assertions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.lang.IllegalStateException
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith

class CordaServiceTest {

    val cordaRPCOps: CordaRPCOps = mock()
    val cordaRPClientWrapper: CordaRPClientWrapper = mock() {
        on(it.proxy()) doReturn cordaRPCOps
    }

    lateinit var cordaService : CordaService

    @Before
    fun setup() {
        cordaService = CordaService()
        cordaService.cordaRPClientWrapper = cordaRPClientWrapper
    }

    @Test
    fun `consentRequestStateByUUID throws NotFoundException when proxy returns empty states`() {
        `when`(cordaRPCOps.vaultQueryBy<ConsentRequestState>(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentRequestState::class.java))).thenReturn(page(0))

        try {
            cordaService.consentRequestStateByUUID("1111-2222-33334444-5555-6666")
            fail("Exception should have been raised")
        } catch (e: NotFoundException) {
            // suc6
        }
    }

    @Test
    fun `consentRequestStateByUUID throws IllegalStateException when proxy returns more than 1 state`() {
        `when`(cordaRPCOps.vaultQueryBy<ConsentRequestState>(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentRequestState::class.java))).thenReturn(page(2))

        try {
            cordaService.consentRequestStateByUUID("1111-2222-33334444-5555-6666")
            fail("Exception should have been raised")
        } catch (e: IllegalStateException) {
            // suc6
        }
    }

    @Test
    fun `consentRequestStateByUUID state data on success`() {
        `when`(cordaRPCOps.vaultQueryBy<ConsentRequestState>(
                criteria = any(),
                paging = any(),
                sorting = any(),
                contractStateType = eq(ConsentRequestState::class.java))).thenReturn(page(1))

        val state = cordaService.consentRequestStateByUUID("1111-2222-33334444-5555-6666")
        assertNotNull(state)
    }

    @Test
    fun `getAttachment returns null for unknown attachment`() {
        val hash = SecureHash.parse("afafafafafafafafafafafafafafafafafafafafafafafafafafafafafafafaf")

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(false)

        val att = cordaService.getAttachment(hash)
    }

    @Test
    fun `getAttachment returns correct data for correct attachment`(){
        val hash = SecureHash.parse("afafafafafafafafafafafafafafafafafafafafafafafafafafafafafafafaf")

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(hash)).thenReturn(zip(consentMetadataAsJson(), "blob"))

        val att = cordaService.getAttachment(hash)

        assertNotNull(att)
        assertTrue(att!!.metadata.domain.contains(Domain.medical))
    }

    @Test
    fun `getAttachment throws IllegalState for missing metadata`(){
        val hash = SecureHash.parse("afafafafafafafafafafafafafafafafafafafafafafafafafafafafafafafaf")

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(hash)).thenReturn(zip(null, "blob"))


        assertFailsWith<IllegalStateException> {
            cordaService.getAttachment(hash)
        }
    }

    @Test
    fun `getAttachment throws IllegalState for missing binary`(){
        val hash = SecureHash.parse("afafafafafafafafafafafafafafafafafafafafafafafafafafafafafafafaf")

        `when`(cordaRPCOps.attachmentExists(hash)).thenReturn(true)
        `when`(cordaRPCOps.openAttachment(hash)).thenReturn(zip(consentMetadataAsJson(), null))


        assertFailsWith<IllegalStateException> {
            cordaService.getAttachment(hash)
        }
    }

    private fun zip(metadata: String?, data: String?) : InputStream {
        val baos = ByteArrayOutputStream(8096)
        val out = ZipOutputStream(baos)

        if (metadata != null) {
            val entry = ZipEntry("metadata.json")
            out.putNextEntry(entry)
            out.write(metadata.toByteArray())
        }

        if (data != null) {
            val entry = ZipEntry("data.bin")
            out.putNextEntry(entry)
            out.write(data.toByteArray())
        }

        out.close()
        baos.close()

        return ByteArrayInputStream(baos.toByteArray())
    }

    private fun page(numberOfResults: Int) : Vault.Page<ConsentRequestState> {
        val states = mutableListOf<StateAndRef<ConsentRequestState>>()

        for (i in 0 until numberOfResults) {
            states.add(
                    StateAndRef(
                            state = TransactionState(mock(), "nl.nuts.consent.contract.ConsentContract", mock()),
                            ref = mock()
                    )
            )
        }

        return Vault.Page(
                states = states,
                otherResults = emptyList(),
                statesMetadata = emptyList(),
                stateTypes = Vault.StateStatus.UNCONSUMED,
                totalStatesAvailable = numberOfResults.toLong())
    }

    private fun consentMetadataAsJson() : String {
        return Serialization.objectMapper().writeValueAsString(consentMetadata())
    }

    private fun consentMetadata() : ConsentMetadata {
        return ConsentMetadata(
                domain = listOf(Domain.medical),
                secureKey = SymmetricKey(
                        alg = "alg",
                        iv = "iv"
                ),
                organisationSecureKeys = emptyList(),
                period = Period(
                        validFrom = LocalDate.now()
                )
        )
    }
}