// swift-tools-version:5.9
// Package.swift 放仓库根：SwiftPM 按 Git URL 引用时只认根目录清单。源码在 ios/ 下。
import PackageDescription

let package = Package(
    name: "CipherVisual",
    platforms: [
        // 边界 §7.1：iOS 13+ 硬门槛；macOS 仅用于跑纯逻辑单测
        .iOS(.v13),
        .macOS(.v10_15),
    ],
    products: [
        .library(name: "CipherVisualCore", targets: ["CipherVisualCore"]),
        .library(name: "CipherVisual", targets: ["CipherVisual"]),
    ],
    targets: [
        // 纯 Swift：IR / PRNG / 原语 / 相位 / 粒子场 / 轨迹 / FSM / 预算 / 引擎（镜像 android/core）
        .target(name: "CipherVisualCore", path: "ios/Sources/CipherVisualCore"),
        // UIKit：CADisplayLink 帧时钟 + 视图渲染 + VisualContent 解析（镜像 android/cipher-visual）
        .target(name: "CipherVisual", dependencies: ["CipherVisualCore"], path: "ios/Sources/CipherVisual"),
        .testTarget(name: "CipherVisualCoreTests", dependencies: ["CipherVisualCore"], path: "ios/Tests/CipherVisualCoreTests"),
    ]
)
