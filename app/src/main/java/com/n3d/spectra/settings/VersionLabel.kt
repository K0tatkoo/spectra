package com.n3d.spectra.settings

/**
 * A version name as it is shown: "2.0.0-beta.1" is the number "2.0.0" with a
 * "BETA 1" badge beside it, "1.5.1" is just the number. Shared by the phone's
 * header and the Windows top bar (the iPhone's About row has a Swift twin), so
 * all three read a pre-release the same way.
 */
data class VersionLabel(val number: String, val stage: String?) {
    companion object {
        private val PRE_RELEASE = Regex("""(\d+(?:\.\d+)*)-(alpha|beta|rc)(?:\.(\d+))?""", RegexOption.IGNORE_CASE)

        fun of(versionName: String): VersionLabel {
            val name = versionName.trim()
            val m = PRE_RELEASE.matchEntire(name) ?: return VersionLabel(name, null)
            val (number, stage, n) = m.destructured
            val word = if (stage.equals("rc", ignoreCase = true)) "RC" else stage.uppercase()
            return VersionLabel(number, if (n.isEmpty()) word else "$word $n")
        }
    }
}
