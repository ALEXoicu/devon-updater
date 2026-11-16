plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val manifestUrl = (findProperty("manifestUrl") as String?)
    ?: "https://github.com/OWNER/devon-updater/releases/latest/download/manifest.json"

android {
    namespace = "com.devon.updater"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.devon.updater"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "MANIFEST_URL", "\"$manifestUrl\"")
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/devon-updater.jks")
            storePassword = findProperty("devonStorePassword") as String? ?: ""
            keyAlias = "devon-updater"
            keyPassword = findProperty("devonStorePassword") as String? ?: ""
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    compileOnly(project(":stubs"))
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
