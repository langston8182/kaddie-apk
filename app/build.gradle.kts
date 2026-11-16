import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Signature release : renseigner kaddie.release.* dans local.properties (jamais versionné).
// Sans ces propriétés, la release est produite mais non signée.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val releaseStoreFile: String? = localProperties.getProperty("kaddie.release.storeFile")?.trim()?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.kaddie.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kaddie.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // Schéma de redirection OAuth (kaddie://auth/callback et kaddie://auth/logout), géré par AppAuth.
        manifestPlaceholders["appAuthRedirectScheme"] = "kaddie"
        buildConfigField("String", "COGNITO_REDIRECT_URI", "\"kaddie://auth/callback\"")
        buildConfigField("String", "COGNITO_LOGOUT_URI", "\"kaddie://auth/logout\"")
    }

    flavorDimensions += "env"
    productFlavors {
        create("preprod") {
            dimension = "env"
            buildConfigField("String", "API_BASE_URL", "\"https://kaddie-api-preprod.cyrilmarchive.com/v1/\"")
            buildConfigField("String", "COGNITO_DOMAIN", "\"https://kaddie-auth-preprod.cyrilmarchive.com\"")
            buildConfigField("String", "COGNITO_CLIENT_ID", "\"2828tgigt5ckmqn9oqoknhfac5\"")
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "API_BASE_URL", "\"https://kaddie-api.cyrilmarchive.com/v1/\"")
            buildConfigField("String", "COGNITO_DOMAIN", "\"https://kaddie-auth.cyrilmarchive.com\"")
            buildConfigField("String", "COGNITO_CLIENT_ID", "\"1idl8aqbcettt4cr716n26i9sk\"")
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProperties.getProperty("kaddie.release.storePassword")
                keyAlias = localProperties.getProperty("kaddie.release.keyAlias")
                keyPassword = localProperties.getProperty("kaddie.release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.appauth)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
