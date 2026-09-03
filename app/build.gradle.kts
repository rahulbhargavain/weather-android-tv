import java.util.Properties

// Reads the four required values from local.properties (gitignored, never
// committed) and exposes them as BuildConfig fields. See local.properties.example
// and README.md "Configuration" section before your first build -- the app
// will not compile until these are set.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun requiredProperty(key: String): String {
    val value = localProperties.getProperty(key)
    require(!value.isNullOrBlank()) {
        "Missing '$key' in local.properties. Copy local.properties.example to " +
            "local.properties and fill in real values before building -- see README.md."
    }
    return value
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bhimtal.dashboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bhimtal.dashboard"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "PWS_STATION_ID", "\"${requiredProperty("PWS_STATION_ID")}\"")
        buildConfigField("String", "PWS_API_KEY", "\"${requiredProperty("PWS_API_KEY")}\"")
        buildConfigField("double", "LATITUDE", requiredProperty("LATITUDE"))
        buildConfigField("double", "LONGITUDE", requiredProperty("LONGITUDE"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
}
