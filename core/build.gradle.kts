import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

fun androidJar(): File {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { props.load(it) }
    val sdk = props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: error("set sdk.dir or ANDROID_HOME")
    return file("$sdk/platforms/android-35/android.jar")
}

fun androidJar26(): File = File(androidJar().parentFile.parentFile, "android-26/android.jar")

dependencies {
    api("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    // Claude API (the official Java SDK; Kotlin-friendly, OkHttp underneath)
    implementation("com.anthropic:anthropic-java:3.9.0")
    // Android ships XmlPullParser in the platform; plain JVM (tests, CLI) gets it from kxml2.
    compileOnly("net.sf.kxml:kxml2:2.3.0")
    // org.json is part of Android; the JVM gets the reference implementation at runtime. Core compiles
    // against Android's own org.json (android.jar) so a method Android lacks fails the build, not the phone.
    compileOnly(files(androidJar()))
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

// prompts/ and config/ (the editable defaults) ship inside core's resources as /prompts and /config
tasks.processResources {
    from(rootProject.file("prompts")) { into("prompts") }
    from(rootProject.file("config")) { into("config") }
}

tasks.test {
    useJUnitPlatform()
    // Real-data tests find JMdict and the deck export here; they skip when the files are absent.
    systemProperty("cramtale.data", rootProject.file("local").absolutePath)
    maxHeapSize = "2g"
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// The same sources compiled against Android 8.0's Java library (minSdk 26) instead of desktop Java, so a
// method older phones lack (or desktop-only classes) fails here: ./gradlew :core:compileOnAndroid26Kotlin.
// Install it with: sdkmanager "platforms;android-26". Part of `check` when it is installed.
val onAndroid26 by sourceSets.creating {
    kotlin.srcDir("src/main/kotlin")
    compileClasspath += configurations.compileClasspath.get().filter { !it.name.startsWith("json-") }
}
dependencies { "onAndroid26CompileOnly"(files(androidJar26())) }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileOnAndroid26Kotlin") {
    compilerOptions.freeCompilerArgs.add("-no-jdk")
    onlyIf { androidJar26().exists() }
}
tasks.named("check") { dependsOn("compileOnAndroid26Kotlin") }
