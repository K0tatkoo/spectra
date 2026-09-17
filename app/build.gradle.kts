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


android {
    namespace = "com.n3d.spectra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.n3d.spectra"
        // 29 is the floor for AudioPlaybackCapture (MediaProjection audio).
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
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
    }
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

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
}
