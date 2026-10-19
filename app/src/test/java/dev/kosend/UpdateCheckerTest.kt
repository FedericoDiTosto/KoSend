package dev.kosend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun newerVersions() {
        assertTrue(UpdateChecker.isNewer("1.0.2", "1.0.1"))
        assertTrue(UpdateChecker.isNewer("v1.1", "1.0.9"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.2"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.99"))
    }

    @Test
    fun sameOrOlderVersions() {
        assertFalse(UpdateChecker.isNewer("1.0.1", "1.0.1"))
        assertFalse(UpdateChecker.isNewer("v1.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.1"))
        assertFalse(UpdateChecker.isNewer("1.0.1-beta", "1.0.1"))
    }
}
