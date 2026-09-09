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
 * The only dependency is the Kotlin stdlib. That is deliberate: it keeps the
 * shipped Windows runtime down to a jlink image of java.desktop and lets the
 * whole thing be cross-built from a Mac.
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
            // Desktop-only.
            "com/n3d/spectra/desktop/**",
        ),
    )
}

dependencies {
    implementation(kotlin("stdlib"))
}

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
