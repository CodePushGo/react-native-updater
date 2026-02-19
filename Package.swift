// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CodePushGoReactNativeUpdaterCore",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CodePushGoReactNativeUpdaterCore",
            targets: ["CodePushGoReactNativeUpdaterCore"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/Alamofire/Alamofire.git", .upToNextMajor(from: "5.10.2")),
        .package(url: "https://github.com/ZipArchive/ZipArchive.git", exact: "2.4.3"),
        .package(url: "https://github.com/mrackwitz/Version.git", exact: "0.8.0"),
        .package(url: "https://github.com/attaswift/BigInt.git", from: "5.5.1")
    ],
    targets: [
        .target(
            name: "CodePushGoReactNativeUpdaterCore",
            dependencies: [
                .product(name: "ZipArchive", package: "ZipArchive"),
                .product(name: "Alamofire", package: "Alamofire"),
                .product(name: "Version", package: "Version"),
                .product(name: "BigInt", package: "BigInt")
            ],
            path: "ios/Plugin",
            exclude: [
                "ReactNativeUpdaterModule.swift",
                "ReactNativeUpdaterModule.m"
            ]
        )
    ],
    swiftLanguageVersions: [.v5]
)
