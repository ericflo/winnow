import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM on purpose: no Android types, so the classification layer is
// unit-testable on the JVM and reusable outside the app (CLI evals, a server proxy).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Rebuilds the bundled on-device model from training/corpus and writes training/REPORT.md.
// LocalModelTest fails if the shipped model is stale, so run this after editing the corpus.
tasks.register<JavaExec>("trainLocalModel") {
    group = "winnow"
    description = "Retrains the on-device model from training/corpus."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ericflo.winnow.classifier.local.TrainLocalModelKt")
    args(
        file("training/corpus").path,
        file("training/eval.tsv").path,
        file("src/main/resources/com/ericflo/winnow/classifier/local/winnow-local.bin").path,
        file("training/REPORT.md").path,
    )
}
