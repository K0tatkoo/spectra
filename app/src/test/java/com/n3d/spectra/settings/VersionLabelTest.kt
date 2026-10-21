package com.n3d.spectra.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the header, the Windows top bar and the iPhone's About row show for a version name. */
class VersionLabelTest {

    @Test
    fun aStableVersionIsJustItsNumber() {
        assertEquals(VersionLabel("1.5.1", null), VersionLabel.of("1.5.1"))
        assertEquals(VersionLabel("2.0", null), VersionLabel.of(" 2.0 "))
    }

    @Test
    fun aPreReleaseSplitsIntoNumberAndBadge() {
        assertEquals(VersionLabel("2.0.0", "BETA 1"), VersionLabel.of("2.0.0-beta.1"))
        assertEquals(VersionLabel("2.0.0", "ALPHA 12"), VersionLabel.of("2.0.0-alpha.12"))
        assertEquals(VersionLabel("2.1.0", "RC 2"), VersionLabel.of("2.1.0-rc.2"))
        assertEquals(VersionLabel("2.0.0", "BETA"), VersionLabel.of("2.0.0-Beta"))
    }

    @Test
    fun anythingElseIsShownAsItIs() {
        assertEquals(VersionLabel("2.0.0-dev", null), VersionLabel.of("2.0.0-dev"))
        assertEquals(VersionLabel("", null), VersionLabel.of(""))
    }
}
