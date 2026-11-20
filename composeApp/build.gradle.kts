import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.googleServices) apply false
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        androidMain.dependencies {
            // compose.preview sengaja tidak di-release (tidak ada @Preview di source).
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.fragment.ktx)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.webkit)
            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.splashscreen)
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.swiperefreshlayout)
            implementation(libs.firebase.messaging)
            implementation(libs.play.services.location)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

android {
    namespace = "co.id.multifab"
    // compileSdk: untuk compile library (WorkManager/core butuh 35).
    compileSdk = 36

    defaultConfig {
        applicationId = "co.id.multifab"
        // minSdk = syarat install. API 23 = Android 6.0 → HP ~2018 tetap bisa.
        minSdk = 23
        // targetSdk harus modern (≥34/35) agar Play Protect tidak blokir
        // "dibangun untuk versi lama Android". Bukan syarat minimum HP.
        targetSdk = 36
        versionCode = 2051
        versionName = "2.6.13"
        // FCM service dimatikan sampai google-services.json tersedia (cegah crash Honor).
        val googleServicesReady = isGoogleServicesReady()
        manifestPlaceholders["smileFcmEnabled"] = if (googleServicesReady) "true" else "false"
        buildConfigField("boolean", "SMILE_FCM_ENABLED", if (googleServicesReady) "true" else "false")

        // HP kentang 2018: fokus ARM 32/64-bit (skip x86 emulator bloat di APK).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
                signingConfigs {
                    create("release") {
                        keyAlias = keystoreProperties["keyAlias"] as String
                        keyPassword = keystoreProperties["keyPassword"] as String
                        storeFile = file(keystoreProperties["storeFile"] as String)
                        storePassword = keystoreProperties["storePassword"] as String
                    }
                }
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

fun isGoogleServicesReady(): Boolean {
    val json = file("google-services.json")
    if (json.exists() && json.readText().isNotBlank()) return true
    generateGoogleServicesFromProperties()
    return json.exists() && json.readText().isNotBlank()
}

fun generateGoogleServicesFromProperties() {
    val propsFile = file("firebase.android.properties")
    if (!propsFile.exists()) return

    val props = Properties().apply {
        propsFile.inputStream().use { load(it) }
    }

    val appId = props.getProperty("android_app_id", "").trim()
    val apiKey = props.getProperty("api_key", "").trim()
    val projectId = props.getProperty("project_id", "").trim()
    val projectNumber = props.getProperty("project_number", "").trim()
    val storageBucket = props.getProperty("storage_bucket", "").trim()
    val packageName = props.getProperty("package_name", "co.id.multifab").trim()

    if (appId.isBlank() || apiKey.isBlank() || projectId.isBlank() || projectNumber.isBlank()) return

    val json = """
        {
          "project_info": {
            "project_number": "$projectNumber",
            "project_id": "$projectId",
            "storage_bucket": "$storageBucket"
          },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "$appId",
                "android_client_info": {
                  "package_name": "$packageName"
                }
              },
              "oauth_client": [],
              "api_key": [
                {
                  "current_key": "$apiKey"
                }
              ],
              "services": {
                "appinvite_service": {
                  "other_platform_oauth_client": []
                }
              }
            }
          ],
          "configuration_version": "1"
        }
    """.trimIndent()

    file("google-services.json").writeText(json)
    logger.lifecycle("Generated composeApp/google-services.json from firebase.android.properties")
}

tasks.register("generateGoogleServices") {
    group = "smile"
    description = "Generate google-services.json from firebase.android.properties"
    doLast {
        generateGoogleServicesFromProperties()
        if (!file("google-services.json").exists()) {
            throw GradleException(
                "firebase.android.properties belum lengkap. Isi android_app_id dari Firebase Console " +
                    "(smile-multifab-v2 → Android co.id.multifab), atau jalankan scripts/sync-fcm-config.ps1"
            )
        }
    }
}

val googleServicesFile = file("google-services.json")
if (isGoogleServicesReady()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.lifecycle(
        "google-services.json not found — FCM disabled. " +
            "Salin dari smile-1.1, export via BE (scripts/sync-fcm-config.ps1), " +
            "atau isi composeApp/firebase.android.properties"
    )
}
