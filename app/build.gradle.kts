import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.nayro.app"
    minSdk = 29
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true; buildConfig = true }
  androidResources { noCompress += listOf("onnx", "tflite", "txt") }
  packaging {
    jniLibs {
      useLegacyPackaging = true
      // Do not use pickFirst for Sherpa/ONNX Runtime. Duplicate native runtimes must fail
      // packaging rather than silently selecting an arbitrary, potentially incompatible library.
    }
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}
googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-2.0.3.aar").asFile
val sherpaKwsDir = layout.projectDirectory.dir("src/main/assets/kws-model").asFile
val sherpaAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

tasks.register("verifySherpaRuntimeAssets") {
  inputs.file(sherpaAar)
  inputs.dir(sherpaKwsDir)
  doLast {
    if (!sherpaAar.isFile || sherpaAar.length() == 0L) {
      throw GradleException("Official Sherpa-ONNX v2.0.3 AAR is missing. Run: bash scripts/prepare-sherpa-onnx-android.sh")
    }
    ZipFile(sherpaAar).use { aar ->
      val entries = aar.entries().asSequence().associateBy { it.name }
      val classesJar = entries["classes.jar"]
        ?: throw GradleException("Invalid Sherpa-ONNX AAR: classes.jar is missing.")
      val requiredClasses = setOf(
        "com/k2fsa/sherpa/onnx/KeywordSpotter.class",
        "com/k2fsa/sherpa/onnx/KeywordSpotterConfig.class",
        "com/k2fsa/sherpa/onnx/OnlineStream.class",
        "com/k2fsa/sherpa/onnx/FeatureConfig.class",
        "com/k2fsa/sherpa/onnx/KeywordSpotterResult.class",
        "com/k2fsa/sherpa/onnx/OnlineModelConfig.class",
        "com/k2fsa/sherpa/onnx/OnlineTransducerModelConfig.class"
      )
      val actualClasses = mutableSetOf<String>()
      ZipInputStream(aar.getInputStream(classesJar)).use { nested ->
        while (true) {
          val entry = nested.nextEntry ?: break
          actualClasses += entry.name
        }
      }
      val missingClasses = requiredClasses - actualClasses
      if (missingClasses.isNotEmpty()) {
        throw GradleException("Sherpa-ONNX AAR is missing official Kotlin API classes: ${missingClasses.joinToString()}")
      }
      for (abi in sherpaAbis) {
        for (library in listOf("libsherpa-onnx-jni.so", "libonnxruntime.so")) {
          val path = "jni/${abi}/${library}"
          val entry = entries[path]
          if (entry == null || entry.size <= 0L) {
            throw GradleException("Sherpa-ONNX AAR is missing required native runtime library: ${path}")
          }
        }
      }
    }

    val requiredModels = listOf(
      "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
      "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
      "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
      "tokens.txt",
      "keywords.txt"
    )
    requiredModels.forEach { modelName ->
      val modelFile = File(sherpaKwsDir, modelName)
      if (!modelFile.isFile || modelFile.length() <= 0L) {
        throw GradleException("Missing or empty Sherpa-ONNX KWS model asset: ${modelFile.path}")
      }
    }
    logger.lifecycle("Verified official Sherpa-ONNX v2.0.3 Kotlin AAR, JNI + ONNX Runtime for ${sherpaAbis.size} ABIs, and KWS model assets.")
  }
}
tasks.named("preBuild") { dependsOn("verifySherpaRuntimeAssets") }

dependencies {
  implementation(files("libs/sherpa-onnx-2.0.3.aar"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // implementation(libs.firebase.firestore)
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
