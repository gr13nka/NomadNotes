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
        // Onyx SDK artifacts, vendored into the repo: repo.boox.com is unreachable from some
        // networks (and VPNs that reach it break dl.google.com), so builds must not depend on it.
        // Re-run the vendoring against repo.boox.com only when bumping the Onyx SDK version.
        maven {
            url = uri("$rootDir/third_party/boox-m2")
        }
        maven {
            url = uri("https://repo.boox.com/repository/maven-public/")
        }
    }
}

rootProject.name = "NomadNotes"

include(":core")
include(":pen-onyx")
include(":app")
