import java.util.Properties

plugins { id("com.android.application") }

val keystoreSettings = Properties()
val keystoreSettingsFile = rootProject.file("keystore.properties")
if (keystoreSettingsFile.exists()) {
    keystoreSettingsFile.inputStream().use { keystoreSettings.load(it) }
}

fun signingValue(property: String, env: String): String? {
    val fromEnv = System.getenv(env)
    if (!fromEnv.isNullOrBlank()) return fromEnv
    val fromFile = keystoreSettings.getProperty(property)
    if (!fromFile.isNullOrBlank()) return fromFile
    return null
}

android {
    namespace = "com.ewan.wallpaperbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ewanyuan.catdiao"
        minSdk = 29
        targetSdk = 35
        versionCode = 200
        versionName = "2.0"
        val commit = (System.getenv("CATDIAO_COMMIT") ?: "dev").replace(Regex("[^0-9A-Za-z._-]"), "")
        buildConfigField("String", "GIT_COMMIT", "\"" + commit + "\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "CATDIAO_KEYSTORE")
            val storePasswordValue = signingValue("storePassword", "CATDIAO_KEYSTORE_PASSWORD")
            val alias = signingValue("keyAlias", "CATDIAO_KEY_ALIAS")
            val keyPasswordValue = signingValue("keyPassword", "CATDIAO_KEY_PASSWORD")
            if (store != null && storePasswordValue != null && alias != null && keyPasswordValue != null
                && file(store).isFile) {
                storeFile = file(store)
                storePassword = storePasswordValue
                keyAlias = alias
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Release APK builds run lintVital, which stops only for fatal issues.
        // NewApi stays fatal so minSdk 29 cannot ship an API 33 field again.
        fatal += "NewApi"
    }
}
