package com.atelierapps.vault.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM test for the source-host parser (spec §6, §2.1). The rest of
 * [SourceAttribution] is Android-framework bound and is exercised on-device.
 */
class SourceAttributionTest {

    @Test fun extractsHostFromSharedUrl() {
        assertEquals("nasa.gov", SourceAttribution.hostOf("https://nasa.gov/image/123"))
        assertEquals("example.com", SourceAttribution.hostOf("Look at this https://www.example.com/x?y=1"))
        assertEquals("news.ycombinator.com", SourceAttribution.hostOf("http://news.ycombinator.com"))
    }

    @Test fun stripsLeadingWww() {
        assertEquals("reddit.com", SourceAttribution.hostOf("https://www.reddit.com/r/x"))
    }

    @Test fun returnsNullWhenNoUrl() {
        assertNull(SourceAttribution.hostOf(null))
        assertNull(SourceAttribution.hostOf(""))
        assertNull(SourceAttribution.hostOf("just some caption text"))
    }
}
