plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qrfloat.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qrfloat.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 30
        versionName = "3.0"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // MQTT 客户端（连公共 broker，无需自建服务器）
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // CameraX
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // ML Kit 条码识别（bundled 模型，运行时不需要谷歌服务，可离线）
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ZXing 用于生成二维码图片
    implementation("com.google.zxing:core:3.5.3")
}
