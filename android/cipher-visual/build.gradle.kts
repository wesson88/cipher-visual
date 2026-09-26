plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.ciphervisual"
    compileSdk = 36

    defaultConfig {
        // 边界 §7.1：minSdk 取 24（避开 API 21–23 渲染怪癖）；高刷 / RenderEffect 等是增强档，不得抬门槛
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        explicitApi()
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.annotation)
}
