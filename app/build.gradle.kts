import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.replay30"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.replay30"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// The gesture model is downloaded at build time so the app can work offline after installation.
val gestureModelFile = file("src/main/assets/gesture_recognizer.task")
val downloadGestureModel by tasks.registering {
    outputs.file(gestureModelFile)
    doLast {
        if (!gestureModelFile.exists() || gestureModelFile.length() < 1_000_000L) {
            gestureModelFile.parentFile.mkdirs()
            val tempFile = file("src/main/assets/gesture_recognizer.task.download")
            val modelUrl = URL("https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task")
            val connection = modelUrl.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.requestMethod = "GET"
            try {
                connection.inputStream.use { input: InputStream ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            if (tempFile.length() < 1_000_000L) {
                tempFile.delete()
                throw GradleException("Download do modelo de gestos incompleto.")
            }
            if (gestureModelFile.exists()) gestureModelFile.delete()
            if (!tempFile.renameTo(gestureModelFile)) {
                tempFile.copyTo(gestureModelFile, overwrite = true)
                tempFile.delete()
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadGestureModel)
}
