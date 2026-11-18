package nl.diments.requestify.core.update

/**
 * A Requestify version as Windows Installer takes it: MAJOR.MINOR.BUILD, MAJOR and MINOR at most 255, BUILD at most
 * 65535. A release's version is its tag (`v0.2.0`); a build on the developer's PC is `0.0.<n>` ([isLocalBuild]) and
 * never updates itself.
 */
data class AppVersion(val major: Int, val minor: Int, val build: Int) : Comparable<AppVersion> {
    val isLocalBuild: Boolean get() = major == 0 && minor == 0

    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::build)

    override fun toString(): String = "$major.$minor.$build"

    companion object {
        private val Pattern = Regex("""v?(\d{1,3})\.(\d{1,3})\.(\d{1,5})""")

        /** `0.2.0` or `v0.2.0`; null for anything else (`dev`, a suffix, a part Windows Installer cannot take). */
        fun parse(text: String): AppVersion? {
            val (major, minor, build) = Pattern.matchEntire(text)?.destructured ?: return null
            val version = AppVersion(major.toInt(), minor.toInt(), build.toInt())
            return version.takeIf { it.major <= 255 && it.minor <= 255 && it.build <= 65535 }
        }

        /** The installed app's version (jpackage's launcher sets it); null under `gradlew run`. */
        fun current(): AppVersion? = System.getProperty("jpackage.app-version")?.let(::parse)
    }
}
