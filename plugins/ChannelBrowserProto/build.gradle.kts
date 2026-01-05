import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf") version "0.9.5"
    id("com.aliucord.plugin")
}

version = "0.1.0"
description = "Manage your channel list from a menu, show/hide channels and follow/unfollow categories"

android {
    compileSdk = 33
    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }
    sourceSets {
        named("main") {
            proto { }
            java {
                // srcDirs("${'$'}{protobuf.generatedFilesBaseDir}/main/javalite") // Commented out to avoid confusion with generated sources
            }
        }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:4.28.3")
    api("com.google.code.gson:gson:2.10.1")
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
}

protobuf {
    val version = "4.28.3"
    protoc {
        artifact = "com.google.protobuf:protoc:$version"
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("java") {
                    option("lite")
                }
            }
        }
    }
}
