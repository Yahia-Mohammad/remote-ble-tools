plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("recordlock") {
            defFile(project.file("src/nativeInterop/cinterop/recordlock.def"))
            includeDirs(project.file("src/nativeInterop/cinterop/include"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.core)
            implementation(libs.serialization.cbor)
            implementation(libs.serialization.json)
            implementation(libs.kaml)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
        }
        // wss:// needs a platform engine; see AgentHttpClient.kt for why CIO cannot serve it.
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        macosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
