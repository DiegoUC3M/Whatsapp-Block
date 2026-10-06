plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val uploadKeystorePath = providers.environmentVariable("PAUSA_CHATS_KEYSTORE_PATH").orNull
val uploadKeystorePassword = providers.environmentVariable("PAUSA_CHATS_KEYSTORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("PAUSA_CHATS_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("PAUSA_CHATS_KEY_PASSWORD").orNull
val uploadSigningConfigured = listOf(
    uploadKeystorePath, uploadKeystorePassword, uploadKeyAlias, uploadKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.diegouc3m.whatsappblock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.diegouc3m.whatsappblock"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (uploadSigningConfigured) {
            create("upload") {
                storeFile = file(requireNotNull(uploadKeystorePath))
                storePassword = requireNotNull(uploadKeystorePassword)
                keyAlias = requireNotNull(uploadKeyAlias)
                keyPassword = requireNotNull(uploadKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

val verifyUploadSigning by tasks.registering {
    doLast {
        check(uploadSigningConfigured) {
            "Release requires an upload key: configure all four PAUSA_CHATS_* signing variables."
        }
        check(file(requireNotNull(uploadKeystorePath)).isFile) {
            "The configured upload keystore does not exist."
        }
    }
}

tasks.configureEach {
    if (name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle", "assembleRelease", "bundleRelease")) {
        dependsOn(verifyUploadSigning)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
