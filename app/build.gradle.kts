import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. The keystore and both passwords live outside every repo, in
// ~/.android/n3d-release.properties, so nothing secret is ever committed. The
// lookup has to sit at the top level: inside the android {} block `java` binds
// to the Android DSL's own member and java.util.Properties stops resolving.
// Without that file the release build still assembles — just unsigned.
val releaseKeyProps = Properties()
val releaseKeyPropsFile = File(System.getProperty("user.home"), ".android/n3d-release.properties")
if (releaseKeyPropsFile.exists()) releaseKeyPropsFile.inputStream().use { releaseKeyProps.load(it) }
val hasReleaseKey = releaseKeyProps.getProperty("storeFile")?.let { path -> File(path).exists() } == true

// The stem model. 37 MB of weights do not belong in git, so the build fetches
// the exact file StemgenRT pins (MIT, github.com/sweetspotsoundsystem/stemgen-rt)
// and refuses any bytes but these. Keep all three in step with StemSeparator.kt.
val stemModelUrl = "https://media.githubusercontent.com/media/sweetspotsoundsystem/stemgen-rt/" +
    "990df8ee5baa621f042d4534dacc65afee0a96ce/model/model.onnx"
val stemModelSha256 = "08424ca91feae8d4746442a35ebf70489dea70ea6e81401b39483cf02d497748"
val stemModelBytes = 37_532_574L

/**
 * Downloads the stem model once into Gradle's own cache, verifies it, and hands
 * it to the APK as a generated asset. A clean build re-copies it from the cache
 * rather than downloading it again.
 */
abstract class FetchStemModel : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val bytes: Property<Long>
    @get:Internal abstract val cacheFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val cache = cacheFile.get().asFile
        if (!isGood(cache)) {
            cache.parentFile.mkdirs()
            val partial = File(cache.path + ".part")
            logger.lifecycle("Downloading the stem model (${bytes.get() / 1_000_000} MB)…")
            URI(url.get()).toURL().openStream().use { input ->
                partial.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            check(isGood(partial)) {
                partial.delete()
                "The downloaded stem model does not match its pinned size and SHA-256."
            }
            check(partial.renameTo(cache)) { "Could not move the stem model into Gradle's cache." }
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val target = File(out, "stems/stemgen-rt-hop128.onnx")
        target.parentFile.mkdirs()
        cache.copyTo(target, overwrite = true)
    }

    private fun isGood(f: File): Boolean {
        if (!f.isFile || f.length() != bytes.get()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256.get()
    }
}

val fetchStemModel = tasks.register<FetchStemModel>("fetchStemModel") {
    url.set(stemModelUrl)
    sha256.set(stemModelSha256)
    bytes.set(stemModelBytes)
    cacheFile.set(File(gradle.gradleUserHomeDir, "caches/spectra/stemgen-rt-${stemModelSha256.take(16)}.onnx"))
    outputDir.set(layout.buildDirectory.dir("generated/stemModel"))
}


android {
    namespace = "com.n3d.spectra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.n3d.spectra"
        // 29 is the floor for AudioPlaybackCapture (MediaProjection audio).
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.1"

        ndk {
            // ONNX Runtime is native code, so the APK now carries it per ABI —
            // about 10 MB each, compressed. Phones are arm64; 32-bit ARM stays
            // so an older phone can still install the rest of the app. No x86:
            // Chromebooks translate ARM apps, and an x86 build would be 12 MB
            // for the emulator alone.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = File(releaseKeyProps.getProperty("storeFile"))
            storePassword = releaseKeyProps.getProperty("storePassword")
            keyAlias = releaseKeyProps.getProperty("keyAlias")
            keyPassword = releaseKeyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // The analysis thread is hot; R8 full mode is fine here, there is
            // no reflection outside Compose's own (already covered) rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compressed in the APK and extracted at install. Stored uncompressed
        // (the modern default) the runtime alone would add ~50 MB to every
        // download of a sideloaded app, to save a moment at install time.
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests.all {
            it.dependsOn(fetchStemModel)
            it.systemProperty(
                "spectra.stemModel",
                fetchStemModel.get().outputDir.get().file("stems/stemgen-rt-hop128.onnx").asFile.absolutePath,
            )
            it.maxHeapSize = "2g"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchStemModel, FetchStemModel::outputDir)
    }
}

// Unit tests run the real separator on this machine's JVM: swap the Android
// build of ONNX Runtime for the desktop one. Same Java API, different natives.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.onnxruntime.jvm)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
}
