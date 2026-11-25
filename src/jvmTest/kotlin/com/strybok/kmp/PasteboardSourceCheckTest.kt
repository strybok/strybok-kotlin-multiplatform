package com.strybok.kmp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * **The SDK never reads the pasteboard** (SDK contract, "Deferred links on iOS", flow A). This
 * is a lint in the shape of a test: it reads the iOS sources of the SDK and fails when anything
 * but the one permitted call — `UIPasteboard.generalPasteboard.detectPatternsForPatterns`, which
 * asks the system whether the content looks like a URL without handing it over — touches a
 * pasteboard. It runs on the JVM because that is where the source files can be read.
 */
class PasteboardSourceCheckTest {
    /** The iOS sources, with comments removed: the documentation may name what the code must not call. */
    private val sources: Map<String, String> = run {
        val root = File("src/iosMain")
        assertTrue(root.isDirectory, "run from the SDK's project directory (${root.absolutePath})")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { file ->
            file.path to file.readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
                .lines().joinToString("\n") { it.substringBefore("//") }
        }
    }

    @Test
    fun theIosSourcesAreThere() {
        assertTrue(sources.size >= 4, "found ${sources.keys}")
        assertTrue(sources.keys.any { it.endsWith("Deferred.ios.kt") })
    }

    @Test
    fun onlyOneFileNamesThePasteboard() {
        val naming = sources.filterValues { "Pasteboard" in it }.keys.map { File(it).name }
        assertEquals(listOf("Deferred.ios.kt"), naming)
    }

    @Test
    fun thePasteboardIsOnlyEverAskedForPatterns() {
        val code = sources.values.joinToString("\n")

        // What is imported from UIKit about pasteboards: the class and the one pattern constant.
        val imported = Regex("""import\s+platform\.UIKit\.(\w*Pasteboard\w*)""").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("UIPasteboard", "UIPasteboardDetectionPatternProbableWebURL"), imported)
        assertTrue(Regex("""import\s+platform\.UIKit\.\*""").find(code) == null, "no wildcard import of UIKit")

        // Every mention of the class is `UIPasteboard.generalPasteboard.detectPatternsForPatterns(`.
        val uses = Regex("""\bUIPasteboard\b([^\n]*)""").findAll(code)
            .map { it.groupValues[1] }
            .filterNot { it.isBlank() } // The import line.
            .toList()
        assertTrue(uses.isNotEmpty())
        for (use in uses) {
            assertTrue(
                Regex("""^\s*\.\s*generalPasteboard\s*\.\s*detectPatternsForPatterns\s*\(""").containsMatchIn(use),
                "UIPasteboard is used for something else: UIPasteboard$use",
            )
        }
        // The pasteboard object is never kept or passed on, so no other member can be reached.
        assertEquals(1, Regex("""\bgeneralPasteboard\b""").findAll(code).count())
        assertEquals(0, Regex("""\bpasteboardWithName|\bpasteboardWithUniqueName""").findAll(code).count())
    }

    @Test
    fun noContentReadingPasteboardApiAppearsAnywhere() {
        // Selectors of UIPasteboard that return or hand over content, as Kotlin/Native names them.
        val forbidden = listOf(
            "valueForPasteboardType", "valuesForPasteboardType", "dataForPasteboardType",
            "itemProviders", "itemSetWithPasteboardTypes", "pasteboardTypes",
            "detectValuesForPatterns", "detectedValuesForPatterns", "inItemSet",
            "hasStrings", "hasURLs", "hasImages", "hasColors", "numberOfItems", "changeCount",
            "UIPasteControl", "NSPasteboard",
        )
        // Properties that read content; only a problem right after a pasteboard expression.
        val members = listOf("string", "strings", "URL", "URLs", "image", "images", "color", "colors", "items")
        for ((path, code) in sources) {
            for (name in forbidden) assertTrue(name !in code, "$path uses $name")
            for (member in members) {
                val read = Regex("""[Pp]asteboard\w*\s*(\(\s*\))?\s*\??\.\s*$member\b""")
                assertTrue(read.find(code) == null, "$path reads `.$member` of a pasteboard")
            }
        }
    }

    /** The JVM target has no deferred links: the options are accepted and ignored. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun onTheJvmTheOptionsAreIgnored() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val strybok = Strybok(
            apiBaseUrl = API, allowedHosts = setOf(HOST),
            transport = { request ->
                requests += request
                HttpResponse(404, "")
            },
            coroutineContext = StandardTestDispatcher(testScheduler),
            deferredLinks = true,
            installReferrerSources = listOf(InstallReferrerSource { InstallReferrerResult.Referrer("strybok_click=$CLICK_ID") }),
            allowProbabilisticMatching = true,
        )
        strybok.start()
        strybok.enableDeferredLinks()
        advanceUntilIdle()

        assertEquals(DeferredLinkStatus.DISABLED, strybok.deferredLinkStatus.value)
        assertEquals(PastedLinkResult.DISABLED, strybok.redeemPastedLink(COPIED))
        assertEquals(false, strybok.pasteboardMayContainLink())
        strybok.skipPastedLink()
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        strybok.dispose()
    }
}
