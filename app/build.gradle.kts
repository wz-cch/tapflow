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
        minSdk = 26
        targetSdk = 35
        versionCode = 1

        // Every CI build otherwise reports the same version, which made it impossible to tell which
        // APK a bug report came from — and with builds landing minutes apart that ambiguity cost real
        // debugging time. CI passes -PbuildId=<sha>; a local build says so.
        val buildId = (project.findProperty("buildId") as String?)?.trim()?.take(7)
        versionName = if (buildId.isNullOrEmpty()) "0.1.0-local" else "0.1.0+$buildId"

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
}
