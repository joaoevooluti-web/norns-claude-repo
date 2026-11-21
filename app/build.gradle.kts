plugins {
    id("com.android.application")
}

android {
    namespace = "org.nornsmobile.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.nornsmobile.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 9
        versionName = "0.4.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // O núcleo scsynth fornecido neste marco foi compilado para ARM64.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    ndkVersion = "27.2.12479018"

    // Chave de assinatura FIXA (guardada no repositório) para que cada novo APK
    // possa ser instalado por cima do anterior, sem desinstalar o app.
    // A keystore fixa não é versionada neste repositório (ver docs/MIGRATION_RECONCILIATION.md).
    // Sem ela, o release cai na chave debug gerada pelo AGP em vez de falhar no empacotamento.
    val nornsKeystore = file("norns-mobile.keystore")

    signingConfigs {
        create("norns") {
            storeFile = file("norns-mobile.keystore")
            storePassword = "nornsmobile"
            keyAlias = "nornsmobile"
            keyPassword = "nornsmobile"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (nornsKeystore.exists()) signingConfigs.getByName("norns")
                            else signingConfigs.getByName("debug")
        }
        // Debug usa a chave de desenvolvimento gerada automaticamente pelo Android Gradle Plugin.
        // A chave privada fixa do projeto é reservada para builds release e não é necessária no CI debug.
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // Interpretador Lua em Java puro (Lua 5.2). Não precisa de NDK.
    implementation("org.luaj:luaj-jse:3.0.1")
}
