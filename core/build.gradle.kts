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

dependencies {
    api("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    // Android ships XmlPullParser in the platform; plain JVM (tests, CLI) gets it from kxml2.
    compileOnly("net.sf.kxml:kxml2:2.3.0")
    // org.json is part of Android; the JVM gets the reference implementation.
    compileOnly("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.test {
    useJUnitPlatform()
    // Real-data tests find JMdict and the deck export here; they skip when the files are absent.
    systemProperty("cramtale.data", rootProject.file("local").absolutePath)
    maxHeapSize = "2g"
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
