pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    // Kotlin Compose Compiler plugin marker is published to Maven Central. Use Google's
    // Maven Central mirror before Gradle Plugin Portal to avoid intermittent marker lookup failures.
    maven {
      url = uri("https://maven-central.storage-download.googleapis.com/maven2")
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

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
