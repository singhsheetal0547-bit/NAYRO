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
