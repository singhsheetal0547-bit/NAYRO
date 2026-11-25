pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    // Google-hosted Maven Central mirror reduces transient 429 responses from repo.maven.apache.org.
    maven {
      url = uri("https://maven-central.storage-download.googleapis.com/maven2")
    }
    mavenCentral()
  }
}

rootProject.name = "NAYRO"

include(":app")
