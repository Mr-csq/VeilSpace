import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.system.launcher.tools"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.system.launcher.tools"
        minSdk = 36
        targetSdk = 36
        versionCode = 7
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Coil - Image Loading
    implementation("io.coil-kt:coil:2.5.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Allow references to generated code
kapt {
    correctErrorTypes = true
}

val expectedAppIconSha256 = "2813c5fe75d12ba91c48c945149d40e778161b4ad9c35c1561df4d4c7f802f89"
val verifyAppIcon by tasks.registering {
    group = "verification"
    description = "Verifies that every build uses the approved VeilSpace app icon."
    val iconFile = layout.projectDirectory.file("src/main/res/drawable/ic_app_icon.png")
    inputs.file(iconFile)

    doLast {
        val actualHash = MessageDigest.getInstance("SHA-256")
            .digest(iconFile.asFile.readBytes())
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        check(actualHash == expectedAppIconSha256) {
            "Approved app icon mismatch: expected $expectedAppIconSha256, actual $actualHash"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyAppIcon)
}