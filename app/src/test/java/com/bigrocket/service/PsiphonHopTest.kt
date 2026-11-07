package com.bigrocket.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.core.ExitRegions
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Protocol

/**
 * The optional "Aether -> Psiphon" hop.
 *
 * These tests pin the two things that can only be verified off-device:
 *  1. the engine is only ever handed `--psiphon` (and a region) for a launch the user
 *     actually asked for, and
 *  2. a chain without Aether can never inherit a saved "on".
 */
class PsiphonHopTest {

    private fun args(profile: ConnectionProfile) = profile.toArgs()

    @Test
    fun `hop off by default adds nothing to the engine command line`() {
        val profile = ConnectionProfile()
        assertFalse(profile.psiphonHop)
        assertFalse(args(profile).contains("--psiphon"))
    }

    @Test
    fun `hop on without a region means automatic and sends no region at all`() {
        val args = args(ConnectionProfile(psiphonHop = true, psiphonRegion = ""))
        assertTrue(args.contains("--psiphon"))
        // "automatic / best" is expressed by NOT passing a region: Psiphon's own
        // EgressRegion is a hard filter, so an empty one must never be sent.
        assertFalse(args.contains("--psiphon-region"))
    }

    @Test
    fun `hop on with a region passes the uppercase two letter code`() {
        val args = args(ConnectionProfile(psiphonHop = true, psiphonRegion = " de "))
        assertTrue(args.contains("--psiphon-region"))
        assertEquals("DE", args[args.indexOf("--psiphon-region") + 1])
    }

    @Test
    fun `a malformed region is dropped instead of reaching argv`() {
        for (bad in listOf("D", "DEU", "D3", "-1", "Germany")) {
            val profile = ConnectionProfile(psiphonHop = true, psiphonRegion = bad)
            assertNull("region '$bad' should be rejected", profile.sanitizedPsiphonRegion())
            assertFalse(args(profile).contains("--psiphon-region"))
        }
    }

    @Test
    fun `region sanitising matches the engine's own rule`() {
        assertEquals("", PsiphonHop.sanitizedRegion(""))
        assertEquals("", PsiphonHop.sanitizedRegion("x"))
        assertEquals("NL", PsiphonHop.sanitizedRegion("nl"))
        assertEquals("TR", PsiphonHop.sanitizedRegion(" Tr "))
    }

    @Test
    fun `the hop only applies to chains that contain aether`() {
        assertTrue(PsiphonHop.appliesTo(EmbeddedAetherRuntime.UpstreamChoice.AETHER))
        assertTrue(PsiphonHop.appliesTo(EmbeddedAetherRuntime.UpstreamChoice.AETHER_XRAY))
        assertTrue(PsiphonHop.appliesTo(EmbeddedAetherRuntime.UpstreamChoice.XRAY_AETHER))
        assertFalse(PsiphonHop.appliesTo(EmbeddedAetherRuntime.UpstreamChoice.NONE))
        assertFalse(PsiphonHop.appliesTo(EmbeddedAetherRuntime.UpstreamChoice.XRAY))
    }

    @Test
    fun `signature separates off, automatic and each country`() {
        assertEquals("off", PsiphonHop.Settings.OFF.signature)
        assertEquals("on:", PsiphonHop.Settings(enabled = true).signature)
        assertEquals("on:DE", PsiphonHop.Settings(enabled = true, region = "de").signature)
        assertTrue(
            PsiphonHop.Settings(enabled = true, region = "DE").signature !=
                PsiphonHop.Settings(enabled = true, region = "NL").signature,
        )
    }

    @Test
    fun `descriptions are human readable`() {
        assertEquals("off", PsiphonHop.describe(PsiphonHop.Settings.OFF))
        assertEquals(
            "on, exit=automatic (best)",
            PsiphonHop.describe(PsiphonHop.Settings(enabled = true)),
        )
        assertEquals(
            "on, exit=DE",
            PsiphonHop.describe(PsiphonHop.Settings(enabled = true, region = "DE")),
        )
    }

    @Test
    fun `the hop never changes which protocol the engine runs`() {
        // The user's own protocol choice must survive the extra hop: Psiphon rides
        // inside the tunnel, it does not replace the tunnel's transport.
        val wireguard = ConnectionProfile(protocol = Protocol.WIREGUARD, psiphonHop = true)
        assertTrue(wireguard.toArgs().contains("--wg"))
        assertTrue(wireguard.toArgs().contains("--psiphon"))
        assertEquals(Protocol.WIREGUARD, wireguard.protocol)
    }

    @Test
    fun `the exact field-log panic is recognised as a broken client`() {
        // This is the .10 field bug verbatim: the client we shipped panicked in
        // psiphon-tls' init() on every start, and the engine could only report
        // "psiphon stopped before it was ready".
        val broken = PsiphonHop.diagnose(
            """[-] psiphon: panic: tls: ConnectionState is not equal to tls.ConnectionState: """ +
                """struct field count mismatch: 18 vs 17""",
        )
        assertTrue(broken != null && broken.contains("سازگار نیست"))
    }

    @Test
    fun `any other panic is reported with its message`() {
        val reason = PsiphonHop.diagnose("panic: runtime error: invalid memory address")
        assertTrue(reason != null && reason.contains("کرش"))
        assertTrue(reason?.contains("invalid memory address") == true)
    }

    @Test
    fun `a healthy client's chatter is not a failure`() {
        // The pre-flight runs the client with a throwaway flag; a normal usage/version
        // line (or nothing at all) must never be mistaken for a problem.
        assertNull(PsiphonHop.diagnose(""))
        assertNull(PsiphonHop.diagnose("   \n  "))
        assertNull(PsiphonHop.diagnose("psiphon-tunnel-core 2.x"))
        assertNull(PsiphonHop.diagnose("flag provided but not defined: -version"))
    }

    @Test
    fun `the automatic row is first in the country list`() {
        // The dropdown's default row must be "automatic", so a user who turns the hop
        // on and changes nothing gets Psiphon's own best exit.
        assertEquals("", ExitRegions.values.first())
        assertEquals(0, ExitRegions.values.indexOf(""))
        assertTrue(ExitRegions.values.contains("DE"))
        assertTrue(ExitRegions.values.all { it.isEmpty() || PsiphonHop.sanitizedRegion(it) == it })
    }
}
