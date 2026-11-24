plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val prebuilt = file("prebuilt")
if (!prebuilt.resolve("include/fpdfview.h").exists()) {
    throw GradleException("PDFium binaries missing. Run tools/fetch-pdfium.sh first.")
}

android {
    namespace = "io.github.guilhermefeitosa66.openpdfviewer.pdfium"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].jniLibs.srcDirs("prebuilt/jniLibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
