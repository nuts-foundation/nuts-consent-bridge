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
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals

class CordappToBridgeTypeTest {
    @Test
    fun `DigitalSignature WithKey is converted correctly`() {
        val base64Sign = "QeztwzJgxCuW+ZlUsUyFn7zESuyEFpPCP546hJdcXarzvsWWuTzA3RFLOIJJRqjz7sccGAcidi+rKDlI1Rj4gOSFLhJKkOABXLt+X2kcqpDguta5/i03j4jAN0dI2Sanp5gc7AHJ0r4791KEYrEbve6rVGN6kSd7kvWFyfTtFgD4R+Yp4T3e5oG5yMFdAmiNK8ko6o8nmzoY0yOWdHneUFaAjGAPkGGGsspQ7U3UYAyVdkXdspF4Ryeh8LbbePFSQkO6Pzj9gVMWBY1LrGIRSPhGQEXj7P6PTar8gs/AkX5gyAQLS383MEcg3fCOiEAbRgQLYsRgo04hl3IChfOW2w=="
        val jwkJson = "{\"kty\":\"RSA\",\"e\":\"AQAB\",\"n\":\"wm7FBfggHaAfapO7TdFv0OwS-Ip9Wi7gyhddjmdZBZDzfYMUPr4-0utGM3Ry8JtCfxmsHL3ZmvG04GV1doeCLjLywm6OFfoEQCpliRiCyarpd2MrxKWjkSwOl9MJdVm3xpb7BWJdXkKEwoU4lBk8cZPay32juPzAV5eb6UCnq53PZ5O0H80J02oPLpBs2D6ASjUQpRf2xP0bvaP2W92PZYzJwrSA3zdxPmrMVApOoIZL7OHBE-y0I9ZUt-zmxD8TzRdN9Etf9wjLD7psu9aL_XHIHR0xMkYV8cr_nCbJ6H0PbDd3yIQvYPjLEVS5LeieN-DzIlYO6Y7kpws6k0rxew\"}"

        val jwk = PublicJsonWebKey.Factory.newPublicJwk(jwkJson)
        val digSign = DigitalSignature.WithKey(jwk.publicKey, Base64.getDecoder().decode(base64Sign))

        val sigWithKey = CordappToBridgeType.convert(digSign)

        assertEquals(jwk.toParams(JsonWebKey.OutputControlLevel.PUBLIC_ONLY), sigWithKey.publicKey)
    }
}