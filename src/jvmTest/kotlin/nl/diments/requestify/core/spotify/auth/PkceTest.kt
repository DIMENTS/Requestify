package nl.diments.requestify.core.spotify.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun rfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val v = Pkce.verifier()
        assertEquals(64, v.length)
        assertTrue(v.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" })
    }
}
