plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.subtrans.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.subtrans.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.activity:activity-ktx:1.9.2")
    // อ่านตัวอักษรจากภาพ (ภาษาอังกฤษ/ละติน) — โมเดลฝังในแอป ใช้ออฟไลน์ได้ทันที
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // อ่านตัวอักษรญี่ปุ่น / จีน / เกาหลี
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // แปลภาษาออฟไลน์ (ดาวน์โหลดโมเดลไทยครั้งแรกประมาณ 30MB)
    implementation("com.google.mlkit:translate:17.0.3")
}
