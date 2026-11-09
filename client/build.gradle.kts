import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Client"
            isStatic = true
            export(projects.clientServer)
            export(libs.lunula.core)
        }
        // Tiny ObjC shim that exposes NSURLProtectionSpace.serverTrust and
        // the NSURLSessionAuthChallengeDisposition constants with concrete
        // Int types. The Kotlin/Native 2.3.20 Foundation binding does not
        // surface `.serverTrust` on the Kotlin side, and the disposition
        // typealias commonizes inconsistently between iosMain metadata and
        // the concrete iosArm64 / iosSimulatorArm64 targets, so the shim
        // is the cleanest way to get strict TOFU pinning on iOS.
        iosTarget.compilations.getByName("main") {
            cinterops {
                val lunamuxTls by creating {
                    definitionFile.set(
                        project.file("src/nativeInterop/cinterop/lunamuxTls.def")
                    )
                    packageName("se.soderbjorn.lunamux.client.tlsinterop")
                }
            }
        }
    }

    jvm {
        // The overview checks live in their own source set: they run against a live server and are
        // tested by jvmTest, but are no part of the shipped client.
        val jvmCompilations = this.compilations
        val checks = jvmCompilations.create("checks") {
            associateWith(jvmCompilations.getByName("main"))
            defaultSourceSet.kotlin.srcDir("src/checks/kotlin")
        }
        jvmCompilations.getByName("test").associateWith(checks)
    }

    js {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.clientServer)
            api(libs.lunula.core)
            api(libs.ktor.client.core)
            api(libs.ktor.client.websockets)
            api(libs.ktor.client.contentNegotiation)
            api(libs.ktor.client.serializationKotlinxJson)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // Backs the Android `SecureStore` actual (EncryptedSharedPreferences)
            // that holds the device-auth token outside the plain local_state.json.
            implementation(libs.androidx.security.crypto)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "se.soderbjorn.lunamux.client"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

// Connects to a running server as a read-only client and prints whether the overview, the footer
// count and the task panel keep their rules (C1 to C7 and the panel's block check). Exits non-zero
// on a FAIL. -Pwatch=<seconds> keeps watching the tabs for that long (C6 needs it); -Pmain=<task
// id>=<start of the conversation id> says which conversation a task's main conversation must be.
tasks.register<JavaExec>("overviewChecks") {
    group = "verification"
    description = "Runs the overview, footer and panel checks against a running server (-Purl, -Ptoken, -Pwatch, -Pmain)."
    val checks = kotlin.targets.getByName("jvm").compilations.getByName("checks")
    dependsOn("jvmChecksClasses")
    classpath(checks.output.allOutputs, checks.runtimeDependencyFiles)
    mainClass.set("se.soderbjorn.lunamux.client.OverviewChecksMainKt")
    val url = providers.gradleProperty("url")
    val token = providers.gradleProperty("token")
    val watch = providers.gradleProperty("watch")
    val main = providers.gradleProperty("main")
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            url.orNull?.let { add("url=$it") }
            token.orNull?.let { add("token=$it") }
            watch.orNull?.let { add("watch=$it") }
            main.orNull?.split(',')?.forEach { add("main=$it") }
        }
    })
}
