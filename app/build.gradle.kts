import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.tapflow.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tapflow.android"

        // Android 7.0. Nothing this app does actually needs more: dispatchGesture and
        // canPerformGestures both arrived in 24, and TYPE_ACCESSIBILITY_OVERLAY in 22. The three
        // things that did require 26 were java.time (now desugared), the adaptive launcher icon (a
        // legacy vector sits in mipmap/ for older releases) and TYPE_APPLICATION_OVERLAY (guarded in
        // OverlayHost). The one genuinely gated capability left is the four-argument
        // StrokeDescription with willContinue, which would only refine swipe fidelity.
        minSdk = 24
        targetSdk = 35
        versionCode = 1

        // Every CI build otherwise reports the same version, which made it impossible to tell which
        // APK a bug report came from — and with builds landing minutes apart that ambiguity cost real
        // debugging time. CI passes -PbuildId=<sha>; a local build says so.
        val buildId = (project.findProperty("buildId") as String?)?.trim()?.take(7)
        versionName = if (buildId.isNullOrEmpty()) "0.1.0-local" else "0.1.0+$buildId"

        // When, not just which. A sha is opaque to a person: telling that 411089d was a week old meant
        // going to the repository to look it up, so "is this the build I was just sent" was unanswerable
        // by either side — and a whole round of debugging went into asking it. A timestamp is legible on
        // sight, and everybody already knows when the APK arrived.
        //
        // It changes every build, so BuildConfig is regenerated every build. That is the point, and the
        // cost is one task in a project that is rebuilt on every change anyway.
        buildConfigField(
            "String",
            "BUILD_TIME",
            "\"" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now()) + "\"",
        )

        // English is the default locale (values/), Traditional Chinese is values-zh-rTW/.
        resourceConfigurations += setOf("en", "zh-rTW")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time is API 26; desugaring makes it work back to minSdk without rewriting the
        // formatting code around SimpleDateFormat.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // For BuildConfig.VERSION_NAME, which the app shows so a bug report identifies its build.
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // The official wrapper over DocumentsContract, for the library folder the user picks. No storage
    // permission is involved — SAF grants access to that one tree and nothing else.
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // repeatOnLifecycle, used to poll the accessibility setting only while resumed. It arrives
    // transitively already; declared so the import cannot break on a future version bump.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
