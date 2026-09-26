pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cipher-visual"

// :core          纯 Kotlin（JVM）——IR / PRNG / 原语 / 相位 / 粒子场 / 轨迹 / FSM / 预算 / 引擎，零 Android 依赖
// :cipher-visual Android 库——Choreographer 帧时钟 + Canvas 渲染 + VisualContent 解析 + facade
// :demo          独立验证 App（边界尺子 3：脱离业务单独跑）+ 30 帧观测
include(":core")
include(":cipher-visual")
include(":demo")
