package com.mink.guardian

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plain file-scan backstops for the module's structural invariants. The Gradle
 * boundary already enforces most of this at compile time (the module cannot see
 * the Android SDK, and `internal` seals Draft/GroundedProse against the app);
 * these scans guard the parts a future dependency edit or an in-module caller
 * could erode without a compile error.
 */
class ArchitectureTest {

    private val mainRoot = File("src/main/kotlin")

    private fun mainSources(): List<File> {
        // The test JVM runs with the module directory as its working dir; fail
        // loudly if that assumption ever breaks rather than passing on nothing.
        assertTrue(
            "expected guardian-core as the working dir, got ${File(".").absolutePath}",
            mainRoot.isDirectory,
        )
        val files = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no Kotlin sources found under $mainRoot", files.isNotEmpty())
        return files
    }

    /**
     * The core never imports Android. The missing SDK dependency enforces this
     * today; the scan keeps it true even if someone adds an Android artifact to
     * this module's dependencies later.
     */
    @Test
    fun coreSourcesNeverImportAndroid() {
        for (file in mainSources()) {
            val offending = file.readLines().filter {
                val line = it.trim()
                line.startsWith("import android.") || line.startsWith("import androidx.")
            }
            assertTrue("${file.path} imports Android: $offending", offending.isEmpty())
        }
    }

    /**
     * Only the composer's GenerationRunner runs the generator. Any other
     * `.generate(` call in core would be a path for model text to bypass the
     * grounding gate.
     */
    @Test
    fun onlyTheComposersRunnerRunsTheGenerator() {
        for (file in mainSources()) {
            if (file.name == "GroundedComposer.kt") continue
            assertTrue(
                "${file.path} calls .generate( — only the composer's runner may run the generator",
                !file.readText().contains(".generate("),
            )
        }
    }

    /** Only the composer accepts the generator seam; nothing else may hold one. */
    @Test
    fun onlyTheComposerImportsTheGeneratorSeam() {
        for (file in mainSources()) {
            if (file.name == "GroundedComposer.kt" || file.name == "TextGenerator.kt") continue
            assertTrue(
                "${file.path} imports TextGenerator — only the composer may accept a generator",
                // startsWith, not equals: an aliased import ("... as TG") must not slip past.
                file.readLines().none { it.trim().startsWith("import com.mink.guardian.llm.TextGenerator") },
            )
        }
    }

    /**
     * Only the composer's gate constructs GroundedProse. Its constructor is
     * private and [GroundedProse.fromGate] is internal, so the app cannot forge
     * one; this scan keeps in-module code honest too.
     */
    @Test
    fun onlyTheComposersGateConstructsGroundedProse() {
        for (file in mainSources()) {
            if (file.name == "GroundedComposer.kt" || file.name == "ComposeContract.kt") continue
            assertTrue(
                "${file.path} calls fromGate( — only the composer's gate constructs GroundedProse",
                !file.readText().contains("fromGate("),
            )
        }
    }
}
