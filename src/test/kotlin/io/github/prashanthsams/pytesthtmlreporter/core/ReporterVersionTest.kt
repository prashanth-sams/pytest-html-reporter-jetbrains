package io.github.prashanthsams.pytesthtmlreporter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReporterVersionTest {

    private fun v(text: String) = ReporterVersion.parse(text)!!

    @Test
    fun `compares release segments numerically`() {
        assertTrue(v("0.3.10") > v("0.3.9"))
        assertTrue(v("0.4.0") > v("0.3.10"))
        assertEquals(0, v("0.4").compareTo(v("0.4.0")))
    }

    @Test
    fun `orders pre-releases before the release and post-releases after it`() {
        assertTrue(v("0.4.3rc1") < v("0.4.3"))
        assertTrue(v("0.4.3.dev0") < v("0.4.3"))
        assertTrue(v("0.4.3.post1") > v("0.4.3"))
        assertTrue(v("0.4.3rc1") > v("0.4.2"))
    }

    @Test
    fun `knows the feature thresholds`() {
        assertTrue(v("0.3.0") >= ReporterVersion.MINIMUM_SUPPORTED)
        assertTrue(v("0.2.9") < ReporterVersion.MINIMUM_SUPPORTED)
        assertTrue(v("0.3.6") < ReporterVersion.REPORT_OPEN)
        assertTrue(v("0.3.7") >= ReporterVersion.REPORT_OPEN)
        assertTrue(v("0.4.3") >= ReporterVersion.CONFIG_COMMAND)
    }

    @Test
    fun `parses tolerant input and rejects garbage`() {
        assertEquals(listOf(1, 2, 3), v(" v1.2.3 ").release)
        assertNull(ReporterVersion.parse(null))
        assertNull(ReporterVersion.parse("not-a-version"))
        assertNull(ReporterVersion.parse(""))
    }
}
