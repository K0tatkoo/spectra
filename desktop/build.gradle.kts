plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Spectra for Windows.
 *
 * A desktop shell around the *same* analysis code as the Android app: the whole
 * `com.n3d.spectra.dsp` package and the `Settings` model are compiled straight
 * out of `app/src/main/java` rather than copied, so the two products can never
 * drift on what a dB or an LUFS means. Everything Android-shaped — capture,
 * painting, persistence, the UI — is re-implemented here against the JDK.
 *
 * Dependencies are kept to the Kotlin stdlib and JNA. That is deliberate: it
 * keeps the shipped Windows runtime down to a small jlink image and lets the
 * whole thing be cross-built from a Mac. JNA is there for one job — reaching
 * WASAPI loopback (system audio), which the JDK has no API for — and it is a
 * plain jar with its native half inside, so it needs no Windows toolchain.
 */
kotlin {
    jvmToolchain(21)
}

sourceSets.main {
    kotlin.srcDir("../app/src/main/java")
    kotlin.setIncludes(
        listOf(
            // Shared with Android, verbatim.
            "com/n3d/spectra/dsp/**",
            "com/n3d/spectra/settings/Settings.kt",
            "com/n3d/spectra/audio/MonoRing.kt",
            "com/n3d/spectra/audio/AudioCapture.kt",
            "com/n3d/spectra/paint/Palette.kt",
            "com/n3d/spectra/paint/Crt.kt",
            // Desktop-only.
            "com/n3d/spectra/desktop/**",
        ),
    )
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.jna)
}

/**
 * JNA carries its native library for some twenty platforms. The shipped jar only
 * ever runs on x64 Windows, and the WASAPI code is never touched anywhere else.
 */
val jnaForeignNatives = listOf(
    "com/sun/jna/aix-*/**", "com/sun/jna/darwin*/**", "com/sun/jna/dragonflybsd-*/**",
    "com/sun/jna/freebsd-*/**", "com/sun/jna/linux-*/**", "com/sun/jna/openbsd-*/**",
    "com/sun/jna/sunos-*/**", "com/sun/jna/win32-x86/**", "com/sun/jna/win32-aarch64/**",
)

val appMainClass = "com.n3d.spectra.desktop.MainKt"

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to appMainClass,
            "Implementation-Title" to "Spectra",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

/**
 * One self-contained jar. No shadow plugin: the only thing to merge is the
 * Kotlin stdlib, and a hand-rolled task avoids pulling a whole plugin (and its
 * transitive Gradle API surface) into a build that has to stay portable.
 */
val fatJar by tasks.registering(Jar::class) {
    archiveBaseName.set("spectra")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to appMainClass,
            "Implementation-Title" to "Spectra",
            "Implementation-Version" to project.version.toString(),
        )
    }
    from(sourceSets.main.get().output) {
        // The development harnesses live in the main source set so they can use
        // internal hooks, but nothing in `dev` belongs in a shipped build.
        exclude("com/n3d/spectra/desktop/dev/**")
    }
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        // Signature files from a signed dependency jar make the merged jar
        // refuse to load. Nothing here is signed today; this keeps it that way.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
        exclude(jnaForeignNatives)
    }
}

tasks.register<JavaExec>("runApp") {
    group = "application"
    description = "Runs Spectra on this machine (macOS) for development."
    mainClass.set(appMainClass)
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("trackerCheck") {
    group = "verification"
    description = "Runs the 2A03 tracker against synthesised chip audio."
    mainClass.set("com.n3d.spectra.desktop.dev.TrackerCheckKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("uiShots") {
    group = "verification"
    description = "Renders each page to build/ui-shots without needing a screen."
    mainClass.set("com.n3d.spectra.desktop.dev.UiShotKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args = listOf("build/ui-shots")
}

/**
 * The fat jar *with* the development harnesses, so they can be run against the
 * cross-built Windows runtime. Never shipped.
 */
val devJar by tasks.registering(Jar::class) {
    archiveBaseName.set("spectra")
    archiveClassifier.set("dev")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to appMainClass) }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    }
}

tasks.register<JavaExec>("appIcon") {
    group = "build"
    description = "Rasterises the Android launcher icon into the PNG set the Windows .ico is built from."
    mainClass.set("com.n3d.spectra.desktop.dev.IconRendererKt")
    classpath = sourceSets.main.get().runtimeClasspath
    // Written into the resources, not into build/: the mark changes about once a
    // year and a committed PNG set is far less fragile than wiring a JavaExec
    // into the resource-processing graph.
    args = listOf("../app/src/main/res/drawable", "src/main/resources/icon", "build/icon")
}

tasks.register<JavaExec>("loopbackCheck") {
    group = "verification"
    description = "Checks the system-audio sample conversion; on Windows also captures what is playing, live."
    mainClass.set("com.n3d.spectra.desktop.dev.LoopbackCheckKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("crtShots") {
    group = "verification"
    description = "Renders the Oscilloscope page from known signals on a simulated clock, to build/crt-shots."
    mainClass.set("com.n3d.spectra.desktop.dev.CrtShotsKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args = listOf("build/crt-shots")
}

tasks.register<JavaExec>("oscDemoWav") {
    group = "verification"
    description = "Writes the built-in oscilloscope demo to build/osc-demo.wav, to play into a phone."
    mainClass.set("com.n3d.spectra.desktop.dev.OscDemoWavKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args = listOf("build/osc-demo.wav")
}
