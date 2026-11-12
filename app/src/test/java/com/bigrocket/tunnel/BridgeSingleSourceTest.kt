package com.bigrocket.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Works in the test" must mean "works as deployed".
 *
 * Every other tunnel test compiles `app/src/test/java/bridgesrc/BridgeServer.kt` - a copy made by
 * `tools/sync-bridge.sh`. This test proves the copy is the shipped file: it re-applies the two
 * edits the sync script makes (a package line, and dropping the `#!/usr/bin/env kotlin` shebang
 * that Kotlin only tolerates on an unpackaged file's first line) and compares byte for byte.
 *
 * If someone edits `far-side/BridgeServer.kt` and forgets `./tools/sync-bridge.sh`, this fails
 * with the exact reason rather than letting the tests drift away from what users run.
 */
class BridgeSingleSourceTest {

    @Test
    fun theTestCopyOfTheFarSideIsByteIdenticalToTheShippedFile() {
        val repoRoot = findRepoRoot()
        val shipped = File(repoRoot, "far-side/BridgeServer.kt")
        val testCopy = File(repoRoot, "app/src/test/java/bridgesrc/BridgeServer.kt")
        assertTrue("missing ${shipped.path} - the far side is part of this feature", shipped.isFile)
        assertTrue("missing ${testCopy.path} - run ./tools/sync-bridge.sh", testCopy.isFile)

        val expected = buildString {
            appendLine("package bridgesrc")
            appendLine()
            shipped.readText().lineSequence()
                .filterNot { it.startsWith("#!/usr/bin/env kotlin") }
                .forEach { appendLine(it) }
        }.trimEnd()

        val actual = testCopy.readText().trimEnd()
        assertEquals(
            "far-side/BridgeServer.kt and its test copy have drifted apart - run ./tools/sync-bridge.sh",
            summarise(expected),
            summarise(actual),
        )
        // Full comparison after the readable one, so a whitespace-only drift is still caught.
        assertEquals(expected, actual)
    }

    /** First differing line, so a failure reads like a diff instead of "strings differ". */
    private fun summarise(text: String): String {
        val lines = text.lines()
        return "sha=${text.hashCode()} lines=${lines.size} first=${lines.firstOrNull()} last=${lines.lastOrNull()}"
    }

    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "far-side/BridgeServer.kt").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repository root from ${File(".").absolutePath}")
    }
}
