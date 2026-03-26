import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    id("org.jetbrains.compose") version "1.6.10"
    id("com.android.application") version "8.3.2"
}

group = "com.messenger"
version = "1.0.0"

val desktopRuntimeJvmArgs = listOf(
    "-Xms256m",
    "-Xmx1024m",
    "-Djava.io.tmpdir=${project.projectDir}/.tmp"
)

kotlin {
    jvmToolchain(17)

    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("io.ktor:ktor-client-core:2.3.8")
                implementation("io.ktor:ktor-client-websockets:2.3.8")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("androidx.activity:activity-compose:1.9.0")
                implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
                implementation("androidx.documentfile:documentfile:1.0.1")
                implementation("io.ktor:ktor-client-okhttp:2.3.8")
                implementation("com.microsoft.signalr:signalr:7.0.14")
                implementation("io.reactivex.rxjava3:rxjava:3.1.8")
                implementation("com.google.code.gson:gson:2.10.1")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-cio:2.3.8")
                implementation("io.ktor:ktor-client-okhttp:2.3.8")
                implementation("com.microsoft.signalr:signalr:7.0.14")
                implementation("io.reactivex.rxjava3:rxjava:3.1.8")
                implementation("com.google.code.gson:gson:2.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
                val osName = System.getProperty("os.name").lowercase()
                val javafxClassifier = when {
                    osName.contains("win") -> "win"
                    osName.contains("mac") -> "mac"
                    else -> "linux"
                }
                implementation("org.openjfx:javafx-base:21.0.2:$javafxClassifier")
                implementation("org.openjfx:javafx-graphics:21.0.2:$javafxClassifier")
                implementation("org.openjfx:javafx-media:21.0.2:$javafxClassifier")
                implementation("org.openjfx:javafx-swing:21.0.2:$javafxClassifier")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.messenger.client.MainKt"
        jvmArgs(*desktopRuntimeJvmArgs.toTypedArray())
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "MessengerClient"
            packageVersion = "1.0.0"
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(*desktopRuntimeJvmArgs.toTypedArray())
}

android {
    namespace = "com.messenger.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.messenger.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
