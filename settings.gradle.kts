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
        // Only for Tesseract4Android (OCR), which is published there.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.adaptech-cz.Tesseract4Android") }
        }
    }
}
rootProject.name = "open-pdf-viewer"
include(":app", ":pdfium")
