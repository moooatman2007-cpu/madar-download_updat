import java.io.File

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  // alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

fun parseEnvContent(content: String): Map<String, String> {
  val envMap = mutableMapOf<String, String>()
  for (line in content.lines()) {
    val trimmed = line.trim().removePrefix("\uFEFF")
    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
      val splitIndex = trimmed.indexOf('=')
      if (splitIndex != -1) {
        val key = trimmed.substring(0, splitIndex).trim().removePrefix("\uFEFF")
        val value = trimmed.substring(splitIndex + 1).trim().trim('"', '\'')
        if (key.isNotEmpty()) {
          envMap[key] = value
        }
      }
    }
  }
  return envMap
}

val rootEnvFileRegular = rootProject.layout.projectDirectory.file(".env")
val rootEnvTextProvider = providers.fileContents(rootEnvFileRegular).asText
val rootEnvMap: Map<String, String> = parseEnvContent(rootEnvTextProvider.orNull ?: "")

fun resolveConfigProperty(key: String, envMap: Map<String, String> = rootEnvMap): String {
  val systemEnv = providers.environmentVariable(key).orNull
  if (!systemEnv.isNullOrBlank()) {
    return systemEnv.trim()
  }
  return envMap[key]?.trim() ?: ""
}

val expectedSupabaseProjectRef = "oknpfsvmopdsgsfbbdcs"

fun validateSupabaseConfig(
  supabaseUrl: String,
  supabaseKey: String,
  supabaseEdgeUrl: String,
  hasEnvFile: Boolean,
  hasSystemEnvUrl: Boolean = false
): List<String> {
  val errors = mutableListOf<String>()

  if (!hasEnvFile && !hasSystemEnvUrl) {
    errors.add("Missing root '.env' file. Builds require a real '.env' file or environment variables containing Supabase client configuration for project '$expectedSupabaseProjectRef'.")
  }

  if (supabaseUrl.isBlank()) {
    errors.add("Missing or empty 'SUPABASE_URL' (set it in root '.env' or environment variables).")
  } else if (supabaseUrl.contains("placeholder", ignoreCase = true)) {
    errors.add("'SUPABASE_URL' contains a placeholder value.")
  } else if (!supabaseUrl.startsWith("https://")) {
    errors.add("'SUPABASE_URL' must start with 'https://'.")
  } else if (!supabaseUrl.contains(expectedSupabaseProjectRef)) {
    errors.add("'SUPABASE_URL' does not match expected Supabase project ref '$expectedSupabaseProjectRef'.")
  }

  if (supabaseKey.isBlank()) {
    errors.add("Missing or empty 'SUPABASE_PUBLISHABLE_KEY' (set it in root '.env' or environment variables).")
  } else if (supabaseKey.contains("placeholder", ignoreCase = true)) {
    errors.add("'SUPABASE_PUBLISHABLE_KEY' contains a placeholder value.")
  } else if (supabaseKey.startsWith("sb_secret_", ignoreCase = true) || supabaseKey.contains("service_role", ignoreCase = true)) {
    errors.add("'SUPABASE_PUBLISHABLE_KEY' must not be a secret/service_role key.")
  }

  if (supabaseEdgeUrl.isBlank()) {
    errors.add("Missing or empty 'SUPABASE_EDGE_FUNCTION_URL' (set it in root '.env' or environment variables).")
  } else if (supabaseEdgeUrl.contains("placeholder", ignoreCase = true)) {
    errors.add("'SUPABASE_EDGE_FUNCTION_URL' contains a placeholder value.")
  } else if (!supabaseEdgeUrl.startsWith("https://")) {
    errors.add("'SUPABASE_EDGE_FUNCTION_URL' must start with 'https://'.")
  } else if (!supabaseEdgeUrl.contains(expectedSupabaseProjectRef)) {
    errors.add("'SUPABASE_EDGE_FUNCTION_URL' does not match expected Supabase project ref '$expectedSupabaseProjectRef'.")
  }

  return errors
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.studentmanager.xpseeg"
    minSdk = 24
    targetSdk = 36
    // CI sets VERSION_CODE (must increase on every Play upload)
    versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    val resolvedSupabaseUrl = resolveConfigProperty("SUPABASE_URL")
    val resolvedSupabasePublishableKey = resolveConfigProperty("SUPABASE_PUBLISHABLE_KEY")
    val resolvedSupabaseEdgeFunctionUrl = resolveConfigProperty("SUPABASE_EDGE_FUNCTION_URL")

    buildConfigField("String", "SUPABASE_URL", "\"$resolvedSupabaseUrl\"")
    buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$resolvedSupabasePublishableKey\"")
    buildConfigField("String", "SUPABASE_EDGE_FUNCTION_URL", "\"$resolvedSupabaseEdgeFunctionUrl\"")
  }


  signingConfigs {
    val releaseKeystorePath = providers.environmentVariable("KEYSTORE_PATH").orNull ?: "${rootDir}/my-upload-key.jks"
    val releaseStorePassword = providers.environmentVariable("STORE_PASSWORD").orNull ?: ""
    val releaseKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull ?: "upload"
    val releaseKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull ?: releaseStorePassword

    create("release") {
      storeFile = file(releaseKeystorePath)
      storePassword = releaseStorePassword
      keyAlias = releaseKeyAlias
      keyPassword = releaseKeyPassword
    }
    // Optional: only used when a shared debug.keystore exists (it is git-ignored).
    // Otherwise AGP falls back to the default ~/.android/debug.keystore.
    create("debugConfig") {
      val sharedDebugKeystore = file("${rootDir}/debug.keystore")
      if (sharedDebugKeystore.exists()) {
        storeFile = sharedDebugKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // STRICT: Release builds MUST ONLY use the production release signing configuration.
      // Under NO circumstances is debugConfig allowed as a fallback.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      val debugConfig = signingConfigs.getByName("debugConfig")
      if (debugConfig.storeFile != null) signingConfig = debugConfig
    }
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
      all {
        it.maxHeapSize = "1024m"
      }
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

val rawSysSupabaseUrl = providers.environmentVariable("SUPABASE_URL")
val rawSysSupabaseKey = providers.environmentVariable("SUPABASE_PUBLISHABLE_KEY")
val rawSysSupabaseEdge = providers.environmentVariable("SUPABASE_EDGE_FUNCTION_URL")

val verifySupabaseConfig = tasks.register("verifySupabaseConfig") {
  description = "Validates that required Supabase client settings (SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, SUPABASE_EDGE_FUNCTION_URL) are configured in .env or environment variables without placeholders."
  group = "verification"

  val envTextProvider = rootEnvTextProvider
  val sysUrlProvider = rawSysSupabaseUrl
  val sysKeyProvider = rawSysSupabaseKey
  val sysEdgeProvider = rawSysSupabaseEdge
  val expectedRef = expectedSupabaseProjectRef

  doFirst {
    fun parseLocalEnv(content: String): Map<String, String> {
      val map = mutableMapOf<String, String>()
      for (line in content.lines()) {
        val trimmed = line.trim().removePrefix("\uFEFF")
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
          val idx = trimmed.indexOf('=')
          if (idx != -1) {
            val k = trimmed.substring(0, idx).trim().removePrefix("\uFEFF")
            val v = trimmed.substring(idx + 1).trim().trim('"', '\'')
            if (k.isNotEmpty()) map[k] = v
          }
        }
      }
      return map
    }

    val rawEnvText = envTextProvider.orNull
    val hasEnv = rawEnvText != null
    val envMap = parseLocalEnv(rawEnvText ?: "")

    fun resolveLocal(sys: String?, key: String): String {
      val s = sys?.trim() ?: ""
      return if (s.isNotEmpty()) s else (envMap[key]?.trim() ?: "")
    }

    val u = resolveLocal(sysUrlProvider.orNull, "SUPABASE_URL")
    val k = resolveLocal(sysKeyProvider.orNull, "SUPABASE_PUBLISHABLE_KEY")
    val e = resolveLocal(sysEdgeProvider.orNull, "SUPABASE_EDGE_FUNCTION_URL")

    val errors = mutableListOf<String>()
    if (!hasEnv && (sysUrlProvider.orNull?.isNotBlank() != true)) {
      errors.add("Missing root '.env' file. Provide '.env' at the project root or set environment variables for Supabase project '$expectedRef'.")
    }
    if (u.isBlank()) {
      errors.add("Missing or empty 'SUPABASE_URL' in '.env' or environment variables.")
    } else if (u.contains("placeholder", ignoreCase = true)) {
      errors.add("'SUPABASE_URL' contains a placeholder value.")
    } else if (!u.startsWith("https://")) {
      errors.add("'SUPABASE_URL' must start with 'https://'.")
    } else if (!u.contains(expectedRef)) {
      errors.add("'SUPABASE_URL' must target Supabase project ref '$expectedRef'.")
    }

    if (k.isBlank()) {
      errors.add("Missing or empty 'SUPABASE_PUBLISHABLE_KEY' in '.env' or environment variables.")
    } else if (k.contains("placeholder", ignoreCase = true)) {
      errors.add("'SUPABASE_PUBLISHABLE_KEY' contains a placeholder value.")
    } else if (k.startsWith("sb_secret_", ignoreCase = true) || k.contains("service_role", ignoreCase = true)) {
      errors.add("'SUPABASE_PUBLISHABLE_KEY' must not be a secret or service_role key.")
    }

    if (e.isBlank()) {
      errors.add("Missing or empty 'SUPABASE_EDGE_FUNCTION_URL' in '.env' or environment variables.")
    } else if (e.contains("placeholder", ignoreCase = true)) {
      errors.add("'SUPABASE_EDGE_FUNCTION_URL' contains a placeholder value.")
    } else if (!e.startsWith("https://")) {
      errors.add("'SUPABASE_EDGE_FUNCTION_URL' must start with 'https://'.")
    } else if (!e.contains(expectedRef)) {
      errors.add("'SUPABASE_EDGE_FUNCTION_URL' must target Supabase project ref '$expectedRef'.")
    }

    if (errors.isNotEmpty()) {
      val errorMessage = buildString {
        appendLine()
        appendLine("==================================================================================")
        appendLine("❌ SUPABASE CONFIGURATION CHECK FAILED (NO PLACEHOLDER / EMPTY BUILDCONFIG ALLOWED):")
        appendLine("==================================================================================")
        errors.forEachIndexed { index, err ->
          appendLine("  [${index + 1}] $err")
        }
        appendLine("==================================================================================")
        appendLine("Please fill SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, and SUPABASE_EDGE_FUNCTION_URL")
        appendLine("in the root '.env' file (or export them as environment variables) for '$expectedRef'.")
        appendLine("==================================================================================")
      }
      throw GradleException(errorMessage)
    }
  }
}

val verifyReleasePreconditions = tasks.register("verifyReleasePreconditions") {
  description = "Validates that all production release preconditions (signing keystore, passwords, and .env Supabase credentials) are properly satisfied."
  group = "verification"
  dependsOn(verifySupabaseConfig)

  val rootDirPath = rootDir.absolutePath
  val keystorePathProvider = providers.environmentVariable("KEYSTORE_PATH")
  val storePasswordProvider = providers.environmentVariable("STORE_PASSWORD")
  val keyAliasProvider = providers.environmentVariable("KEY_ALIAS")
  val keyPasswordProvider = providers.environmentVariable("KEY_PASSWORD")

  doFirst {
    val errors = mutableListOf<String>()

    // 1. Keystore file check
    val releaseKeystorePath = keystorePathProvider.orNull ?: "$rootDirPath/my-upload-key.jks"
    val keystoreFile = File(releaseKeystorePath)
    if (!keystoreFile.exists()) {
      errors.add("Production Keystore file missing at: ${keystoreFile.absolutePath} (Set KEYSTORE_PATH env var or place 'my-upload-key.jks' in root directory).")
    }

    // 2. STORE_PASSWORD check
    val storePassword = storePasswordProvider.orNull
    if (storePassword.isNullOrBlank()) {
      errors.add("Missing or empty 'STORE_PASSWORD' environment variable.")
    }

    // 3. KEY_ALIAS check
    val keyAlias = keyAliasProvider.orNull ?: "upload"
    if (keyAlias.isBlank()) {
      errors.add("Missing or empty 'KEY_ALIAS' environment variable.")
    }

    // 4. KEY_PASSWORD check
    val keyPassword = keyPasswordProvider.orNull ?: storePassword
    if (keyPassword.isNullOrBlank()) {
      errors.add("Missing or empty 'KEY_PASSWORD' environment variable.")
    }

    if (errors.isNotEmpty()) {
      val errorMessage = buildString {
        appendLine()
        appendLine("==================================================================================")
        appendLine("❌ PRODUCTION BUILD SAFETY GATE FAILED:")
        appendLine("==================================================================================")
        errors.forEachIndexed { index, err ->
          appendLine("  [${index + 1}] $err")
        }
        appendLine("==================================================================================")
        appendLine("Release build cannot proceed with missing or invalid production credentials.")
        appendLine("Please provide the required keystore and .env configurations before building release.")
        appendLine("==================================================================================")
      }
      throw GradleException(errorMessage)
    }
  }
}

tasks.matching {
  it.name in listOf(
    "preDebugBuild",
    "generateDebugBuildConfig",
    "compileDebugKotlin",
    "assembleDebug",
    "packageDebug",
    "bundleDebug"
  )
}.configureEach {
  dependsOn(verifySupabaseConfig)
}

tasks.matching {
  it.name in listOf(
    "preReleaseBuild",
    "generateReleaseBuildConfig",
    "compileReleaseKotlin",
    "bundleRelease",
    "assembleRelease",
    "packageReleaseBundle",
    "signReleaseBundle",
    "validateSigningRelease"
  )
}.configureEach {
  dependsOn(verifyReleasePreconditions)
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.mlkit.barcode.scanning)
  implementation(libs.zxing.core)
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
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(platform(libs.supabase.bom))
  implementation(libs.supabase.postgrest)
  implementation(libs.supabase.auth)
  implementation(libs.supabase.storage)
  implementation(libs.ktor.client.okhttp)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // testImplementation(libs.androidx.work.testing)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
}
