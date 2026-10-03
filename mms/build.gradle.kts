import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM on purpose: no Android types, so the MMS PDU codec is unit-testable on the JVM.
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
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}
