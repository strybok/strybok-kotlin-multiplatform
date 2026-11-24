package com.strybok.kmp

import com.strybok.kmp.testing.FakeLinkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/** The example from docs/kotlin-multiplatform.md, using the public API only. */
class DocExampleTest {
    @Test
    fun opensTheInvite() = runTest {
        val source = FakeLinkSource(initialLink = "https://my-app.strybok.link/invite777")
        val strybok = Strybok(
            apiBaseUrl = "https://api.strybok.com",
            allowedHosts = setOf("my-app.strybok.link"),
            linkSource = source,
            transport = { request ->
                if (request.method == "GET") {
                    HttpResponse(200, """{"linkId":"l1","appId":"a1","routePath":"/invite/abc123"}""")
                } else {
                    HttpResponse(202, "")
                }
            },
            coroutineContext = StandardTestDispatcher(testScheduler),
        )

        assertEquals("/invite/abc123", strybok.getInitialLink()?.routePath)

        source.emit("https://my-app.strybok.link/other") // a link while the app runs
        assertEquals("/invite/abc123", strybok.links.first().routePath)
        strybok.dispose()
    }
}
