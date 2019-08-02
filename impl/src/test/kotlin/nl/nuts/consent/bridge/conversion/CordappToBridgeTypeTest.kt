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

package nl.nuts.consent.bridge.conversion

import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.SecureHash
import nl.nuts.consent.bridge.conversion.BridgeToCordappTypeTest.Companion.pemToPub
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals

class CordappToBridgeTypeTest {
    @Test
    fun `DigitalSignature WithKey is converted correctly`() {
        val attHex = "4D15851551A9E5DAF8114C98D0F8D4B18CC97ABD31424D5EA9E3CC84C5F9B45C"
        val base64Sign = "QeztwzJgxCuW+ZlUsUyFn7zESuyEFpPCP546hJdcXarzvsWWuTzA3RFLOIJJRqjz7sccGAcidi+rKDlI1Rj4gOSFLhJKkOABXLt+X2kcqpDguta5/i03j4jAN0dI2Sanp5gc7AHJ0r4791KEYrEbve6rVGN6kSd7kvWFyfTtFgD4R+Yp4T3e5oG5yMFdAmiNK8ko6o8nmzoY0yOWdHneUFaAjGAPkGGGsspQ7U3UYAyVdkXdspF4Ryeh8LbbePFSQkO6Pzj9gVMWBY1LrGIRSPhGQEXj7P6PTar8gs/AkX5gyAQLS383MEcg3fCOiEAbRgQLYsRgo04hl3IChfOW2w=="
        val pemPub = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwm7FBfggHaAfapO7TdFv\n0OwS+Ip9Wi7gyhddjmdZBZDzfYMUPr4+0utGM3Ry8JtCfxmsHL3ZmvG04GV1doeC\nLjLywm6OFfoEQCpliRiCyarpd2MrxKWjkSwOl9MJdVm3xpb7BWJdXkKEwoU4lBk8\ncZPay32juPzAV5eb6UCnq53PZ5O0H80J02oPLpBs2D6ASjUQpRf2xP0bvaP2W92P\nZYzJwrSA3zdxPmrMVApOoIZL7OHBE+y0I9ZUt+zmxD8TzRdN9Etf9wjLD7psu9aL\n/XHIHR0xMkYV8cr/nCbJ6H0PbDd3yIQvYPjLEVS5LeieN+DzIlYO6Y7kpws6k0rx\newIDAQAB\n-----END PUBLIC KEY-----\n"

        val pubKey = pemToPub(pemPub)
        val secureHash = SecureHash.parse(attHex)
        val digSign = DigitalSignature.WithKey(pubKey, Base64.getDecoder().decode(base64Sign))

        val sigWithKey = CordappToBridgeType.convert(digSign)

        assertEquals(pemPub, sigWithKey.publicKey)
    }
}