plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ana.assistente"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ana.assistente"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.$versionCode"
    }

    signingConfigs {
        create("ana") {
            storeFile = file("../keystore/ana.jks")
            storePassword = "anaana"
            keyAlias = "ana"
            keyPassword = "anaana"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("ana") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ana")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // a interface (HTML) é a mesma do app web, copiada da pasta /ana
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("webassets"))
    lint { abortOnError = false; checkReleaseBuilds = false }
}

val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../ana")) { include("index.html", "icon.svg", "manifest.webmanifest") }
    into(layout.buildDirectory.dir("webassets/www"))
}
tasks.named("preBuild") { dependsOn(copyWeb) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
