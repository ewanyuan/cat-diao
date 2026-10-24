plugins { id("com.android.application") }

android {
    namespace = "com.ewan.wallpaperbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ewan.wallpaperbridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 24
        versionName = "1.14"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
