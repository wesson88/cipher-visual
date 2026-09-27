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
    testOptions {
        // android.jar 桩方法返回默认值而非抛错；需要行为的 Android 类用 mockk 打桩
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnitPlatform() }
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
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
