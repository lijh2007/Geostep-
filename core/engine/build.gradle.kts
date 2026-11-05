plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// 没有 NDK/CMake 的机器可用 -PprebuiltNative=true 跳过原生编译：
// 此时改用 src/main/jniLibs 下预置的 libmockgeo_art.so，Kotlin/Java 侧行为不变。
val prebuiltNative = providers.gradleProperty("prebuiltNative").map(String::toBooleanStrict).getOrElse(false)

android {
    namespace = "com.geostep.core.engine"
    compileSdk = 36
    if (!prebuiltNative) {
        ndkVersion = "27.0.12077973"
    }

    defaultConfig {
        minSdk = 26
        if (!prebuiltNative) {
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                }
            }
        }
    }

    if (!prebuiltNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    implementation(files("libs/libxposed-api-102.jar"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
