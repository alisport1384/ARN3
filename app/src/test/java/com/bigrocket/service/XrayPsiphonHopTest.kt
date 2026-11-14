package com.bigrocket.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * The `direct + xray` Psiphon hop.
 *
 * The Aether chains let the engine run Psiphon inside its own tunnel (`aether --psiphon`).
 * Xray has no such stage, so this chain runs the psiphon-tunnel-core client itself and
 * chains it in FRONT of Xray by pointing the client's `UpstreamProxyURL` at Xray's local
 * SOCKS listener.
 *
 * What these tests pin is exactly the part that can go wrong silently:
 *  1. the hop is offered for the chains whose outermost stage is Xray, and for no others;
 *  2. the generated config always routes the client's own dials through the chain - a config
 *     without `UpstreamProxyURL` would still "work", but the exit would be the user's
 *     outbound instead of Psiphon, so the switch would look broken while everything was up;
 *  3. an empty region is *omitted*, never emitted as `""` - Psiphon treats any EgressRegion
 *     as a hard filter, so an empty one yields no tunnel at all.
 */
class XrayPsiphonHopTest {

    private fun config(
        region: String = "",
        upstreamProxy: String = "socks5://127.0.0.1:1839",
    ): JSONObject = JSONObject(
        XrayPsiphonHop.buildConfig(
            dataRoot = "/data/user/0/com.bigrocket/files/psiphon/abc",
            socksPort = 1821,
            upstreamProxy = upstreamProxy,
            region = region,
            serverEntries = null,
        ),
    )

    // -------------------------------------------------------------------------------------
    // 1. which chains own the hop
    // -------------------------------------------------------------------------------------

    @Test
    fun `the hop is offered exactly where Xray is the outermost stage`() {
        assertTrue(XrayPsiphonHop.appliesTo(EmbeddedAetherRuntime.UpstreamChoice.XRAY))

        // Every chain that CONTAINS Aether already exits through the engine's own Psiphon
        // stage (PsiphonHop). Putting this client in front of one as well would be a silent
        // double hop - including AETHER_XRAY, where Xray is technically outermost but the
        // chain underneath is already hopped.
        assertFalse(XrayPsiphonHop.appliesTo(EmbeddedAetherRuntime.UpstreamChoice.AETHER))
        assertFalse(XrayPsiphonHop.appliesTo(EmbeddedAetherRuntime.UpstreamChoice.AETHER_XRAY))
        assertFalse(XrayPsiphonHop.appliesTo(EmbeddedAetherRuntime.UpstreamChoice.XRAY_AETHER))
        assertFalse(XrayPsiphonHop.appliesTo(EmbeddedAetherRuntime.UpstreamChoice.NONE))
    }

    @Test
    fun `direct plus xray and direct plus aether are still covered by one of the two hops`() {
        // The switch must appear for every chain that has a proxy stage, and for none that
        // has not. This is the invariant the UI relies on.
        for (choice in EmbeddedAetherRuntime.UpstreamChoice.entries) {
            val covered = PsiphonHop.appliesTo(choice) || XrayPsiphonHop.appliesTo(choice)
            assertEquals(
                "chain $choice must have exactly one Psiphon mechanism",
                choice != EmbeddedAetherRuntime.UpstreamChoice.NONE,
                covered,
            )
        }
    }

    @Test
    fun `the two mechanisms never claim the same chain`() {
        for (choice in EmbeddedAetherRuntime.UpstreamChoice.entries) {
            assertFalse(
                "chain $choice is claimed by both hops",
                PsiphonHop.appliesTo(choice) && XrayPsiphonHop.appliesTo(choice),
            )
        }
    }

    // -------------------------------------------------------------------------------------
    // 2. the chain itself
    // -------------------------------------------------------------------------------------

    @Test
    fun `the client dials its own tunnel through the chain not straight out`() {
        val json = config(upstreamProxy = "socks5://127.0.0.1:1839")
        assertEquals(
            "socks5://127.0.0.1:1839",
            json.getString("UpstreamProxyURL"),
        )
    }

    @Test
    fun `the client listens on the port the app consumes as the chain entry`() {
        val json = config()
        assertEquals(XrayPsiphonHop.SOCKS_PORT, json.getInt("LocalSocksProxyPort"))
        // The Aether chain uses the same number for its in-engine stage; the two are never
        // live together, and one number means one table everywhere else in the app.
        assertEquals(PsiphonHop.BIND_PORT, XrayPsiphonHop.SOCKS_PORT)
    }

    @Test
    fun `the http proxy is disabled so it cannot shadow the socks listener`() {
        val json = config()
        assertTrue(json.getBoolean("DisableLocalHTTPProxy"))
        assertEquals(0, json.getInt("LocalHttpProxyPort"))
    }

    @Test
    fun `a data root is always set so the client does not write beside itself`() {
        assertTrue(config().getString("DataRootDirectory").isNotEmpty())
    }

    // -------------------------------------------------------------------------------------
    // 3. region handling
    // -------------------------------------------------------------------------------------

    @Test
    fun `automatic omits the region entirely`() {
        assertFalse(config(region = "").has("EgressRegion"))
        assertNull(XrayPsiphonHop.sanitizeRegion("  "))
    }

    @Test
    fun `a real region is emitted uppercase`() {
        assertEquals("DE", config(region = " de ").getString("EgressRegion"))
    }

    @Test
    fun `a malformed region never reaches the config`() {
        // Psiphon would treat any of these as a hard filter and find no tunnel at all.
        for (bad in listOf("D", "DEU", "D3", "-1", "Germany", "1", "??")) {
            assertNull("region '$bad' must be dropped", XrayPsiphonHop.sanitizeRegion(bad))
            assertFalse(
                "region '$bad' must not appear in the config",
                config(region = bad).has("EgressRegion"),
            )
        }
    }

    @Test
    fun `the config is valid json with no trailing comma`() {
        // A trailing comma makes the Go parser reject the whole file, and the client then
        // exits with "invalid config" - which reads like a broken build, not a bug here.
        val text = XrayPsiphonHop.buildConfig(
            dataRoot = "/tmp/x",
            socksPort = 1821,
            upstreamProxy = "socks5://127.0.0.1:1839",
            region = "US",
            serverEntries = "/tmp/entries.txt",
        )
        assertFalse("trailing comma before }", Regex(",\\s*}").containsMatchIn(text))
        JSONObject(text) // throws when the text is not valid JSON
    }
}
