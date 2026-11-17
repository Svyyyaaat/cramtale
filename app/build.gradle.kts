plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // screenshot tests: renders Compose screens to PNGs on the JVM (./gradlew :app:recordPaparazziDebug)
    id("app.cash.paparazzi")
}

android {
    namespace = "com.cramtale.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cramtale.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        resources.excludes += listOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/*.kotlin_module", "META-INF/FastDoubleParser-*")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation("junit:junit:4.13.2")
    // runs core on Android's own libraries (org.json, …) on the JVM: catches desktop-only API use before the phone does
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Robolectric's Android libraries, fetched by Gradle (Robolectric's own downloader can't use a proxy's
// certificates) and handed to it offline.
// one configuration each: in one, Gradle would keep only the newer version
val robolectricSdks = listOf(
    "8.0.0_r4-robolectric-r1-i7",   // minSdk 26
    "15-robolectric-12650502-i7",   // targetSdk 35
).mapIndexed { i, v ->
    configurations.create("robolectricSdk$i").also { dependencies.add(it.name, "org.robolectric:android-all-instrumented:$v") }
}
val robolectricDir = layout.buildDirectory.dir("robolectric-sdks")
val copyRobolectricSdks by tasks.registering(Copy::class) {
    from(robolectricSdks)
    into(robolectricDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricSdks)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
}
