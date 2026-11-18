import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yuushilabs.pianosheet"
    compileSdk = 34

    // 0.4.5: the in-app updater installs the APK the CI publishes, and Android
    // only installs a newer APK signed with the *same* key.  A runner-generated
    // debug key differs on every build, so debug builds sign with the keystore
    // committed at keystore/pianosheet-debug.keystore (password `android`,
    // alias `androiddebugkey`).  Without it the default debug key is used and
    // that build simply cannot update an installed copy.
    signingConfigs {
        getByName("debug") {
            val shared = rootProject.file("keystore/pianosheet-debug.keystore")
            if (shared.exists()) {
                storeFile = shared
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.yuushilabs.pianosheet"
        minSdk = 29
        targetSdk = 34
        versionCode = 15
        versionName = "0.4.8-phase2"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // The ONNX weights are read by ONNX Runtime; keep them stored uncompressed so
    // the runtime can map them instead of copying a compressed stream.  This
    // matters more for the 94 MB PTI model: compressing it would cost ~30 s of
    // install time and force a full decompression into memory at load.
    androidResources {
        noCompress += "onnx"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // The exports are also written to stdout: CI keeps the generated
        // MusicXML/MIDI bytes next to the test result so an outside reader can
        // be pointed at exactly what the app produced.
        unitTests.all {
            it.testLogging {
                showStandardStreams = true
            }
            // ScoreViewerAssetsTest reads the bundled viewer from disk: the assets
            // are not on a plain JVM test's classpath.
            it.systemProperty("pianosheet.assetsDir", file("src/main/assets").absolutePath)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

/**
 * PTI weights: 94.1 MB, not committed (the repository would grow past what a
 * clone/push can carry), fetched once into assets and checked against the
 * published SHA-256.  `-PskipPtiModel=true` builds an APK without them, which
 * the app survives by falling back to Basic Pitch.
 */
val ptiModelFile = layout.projectDirectory.file("src/main/assets/models/piano-notes-v1.onnx").asFile
val ptiModelUrl = "https://huggingface.co/Clement11800/pianino-model/resolve/main/piano-notes-v1.onnx"
val ptiModelSha256 = "9e33ffc0382fb023e0b1aac2abd0c3cb26a5d56fbcc246d7f2c0fc33c569f2ba"
val ptiModelBytes = 98_689_388L
val skipPtiModel = (project.findProperty("skipPtiModel") as String?)?.toBoolean() ?: false

val fetchPtiModel by tasks.registering {
    group = "build"
    description = "Download the PTI-ONNX model into assets if it is missing or the wrong size."
    val target = ptiModelFile
    val url = ptiModelUrl
    val expectedSha = ptiModelSha256
    val expectedBytes = ptiModelBytes
    val skip = skipPtiModel
    onlyIf { !skip && (!target.exists() || target.length() != expectedBytes) }
    doLast {
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        logger.lifecycle("fetching the PTI model: $url")
        URI(url).toURL().openStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(temp.readBytes()).joinToString("") { "%02x".format(it) }
        if (temp.length() != expectedBytes || digest != expectedSha) {
            temp.delete()
            throw GradleException(
                "PTI model download did not match: ${temp.length()} bytes, sha256 $digest"
            )
        }
        if (target.exists()) target.delete()
        temp.renameTo(target)
        logger.lifecycle("PTI model ready: ${target.length()} bytes")
    }
}

if (!skipPtiModel) {
    tasks.named("preBuild") { dependsOn(fetchPtiModel) }
}
