import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    // The wake-word pipeline is written against the ONNX Runtime API; the app supplies the Android
    // build of it, and the tests here run the same code on the desktop build.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
