import org.gradle.api.artifacts.VersionCatalogsExtension
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.audioextractor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.audioextractor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("long", "TIMESTAMP", System.currentTimeMillis().toString() + "L")

        val newpipeExtractorVersion = project.getVersionByName("newpipeextractor") // Legge da libs.versions.toml
        buildConfigField("String", "NEWPIPE_EXTRACTOR_VERSION", "\"$newpipeExtractorVersion\"")

        // Puoi aggiungere altri come questo, ad esempio per Media3 ExoPlayer:
        val media3ExoplayerVersion = project.getVersionByName("media3Exoplayer")
        buildConfigField("String", "MEDIA3_EXOPLAYER_VERSION", "\"$media3ExoplayerVersion\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"

            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // buildFeatures { compose = true } // Rimosso, duplicato
    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // versione del compiler extension compatibile con le versioni consigliate di Compose
        kotlinCompilerExtensionVersion = "1.5.13"
    }

// opzionale: evita problemi con packaging (se usi resource merging con compose)
    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    // --- Android Core (dal tuo libs.versions.toml) ---
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.core.splashscreen)

    // --- Android Lifecycle (dal tuo libs.versions.toml) ---
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime) //
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.service)

    // --- Design (dal tuo libs.versions.toml) ---
    implementation(libs.material)

    // --- Media3-Exoplayer (dal tuo libs.versions.toml) ---
    //implementation(libs.androidx.media3.exoplayer)
    //implementation(libs.androidx.media3.session)
    //implementation(libs.androidx.media3.ui)
    implementation("androidx.media:media:1.7.1")
    //implementation("androidx.media3:media3-session:1.8.0")
    //implementation("androidx.media3:media3-ui:1.8.0")
    //implementation("androidx.media3:media3-exoplayer:1.8.0")

    // --- Retrofit e Kotlinx Serialization (dal tuo libs.versions.toml) ---
    implementation(libs.square.retrofit)
    implementation(libs.logging.interceptor)
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.datetime)
    implementation(libs.converter.kotlinx.serialization)

    // --- NewPipeExtractor (dal tuo libs.versions.toml) ---
    implementation(libs.newpipeextractor)
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.exoplayer)

    // --- Coil (dal tuo libs.versions.toml) ---
    coreLibraryDesugaring(libs.desugaring)
    implementation("io.coil-kt:coil:2.5.0")


    // --- Testing (dal tuo libs.versions.toml) ---
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espressoCore)

    // --- Jetpack Compose (aggiunte) ---
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")                         // UI core
    implementation("androidx.compose.material3:material3")           // Material3
    implementation("androidx.compose.ui:ui-tooling-preview")        // preview
    debugImplementation("androidx.compose.ui:ui-tooling")           // tooling
    implementation("androidx.activity:activity-compose:1.8.0")      // Activity integration
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2") // lifecycle for compose

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

}
fun Project.getVersionByName(name: String): String {
    val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    return versionCatalog.findVersion(name).get().requiredVersion
}