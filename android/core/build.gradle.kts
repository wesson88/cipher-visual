plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 产物给 Android 库消费：字节码压到 17，本机 JDK 21 即可构建，不要求装 17 toolchain
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // golden 向量是仓库级契约产物（contracts/golden），测试直接读仓库文件，不拷贝
    systemProperty("cv.golden.dir", rootProject.file("../contracts/golden").absolutePath)
    testLogging {
        events("failed", "skipped")
        setExceptionFormat("full")
    }
}
