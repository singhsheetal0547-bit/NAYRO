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
    mavenCentral()
    exclusiveContent {
      forRepository {
        ivy {
          name = "SherpaOnnxGitHubReleases"
          url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
          patternLayout {
            artifact("v[revision]/[artifact]-[revision].[ext]")
          }
          metadataSources {
            artifact()
          }
        }
      }
      filter {
        includeGroup("com.k2fsa.sherpa")
      }
    }
  }
}

rootProject.name = "NAYRO"

include(":app")
