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

// The hand-written training texts go into the library too, so a model can be trained from scratch on the phone.
val corpusResources = layout.buildDirectory.dir("generated/corpus")
val bundleCorpus = tasks.register<Sync>("bundleCorpus") {
    from("training/corpus")
    into(corpusResources.map { it.dir("com/ericflo/winnow/classifier/local/corpus") })
}
sourceSets["main"].resources.srcDir(corpusResources)
tasks.named("processResources") { dependsOn(bundleCorpus) }

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
        file("src/main/resources/com/ericflo/winnow/classifier/local/winnow-local-metrics.json").path,
        file("training/REPORT.md").path,
    )
}

// Lists the bundled model's mistakes on training/eval.tsv.
tasks.register<JavaExec>("evalMistakes") {
    group = "winnow"
    description = "Prints the on-device model's mistakes on the evaluation set."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ericflo.winnow.classifier.local.EvalMistakesKt")
}

tasks.register<JavaExec>("tuneLocalModel") {
    group = "winnow"
    description = "Grid-searches the on-device model's training settings by cross-validation."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ericflo.winnow.classifier.local.TuneLocalModelKt")
}

tasks.register<JavaExec>("filterPolicyExperiment") {
    group = "winnow"
    description = "Compares filtering rules and model variants by false positives and recall."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ericflo.winnow.classifier.local.FilterPolicyExperimentKt")
}

tasks.register<JavaExec>("deepExperiment") {
    group = "winnow"
    description = "Cross-validates a wide & deep variant against the linear model."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ericflo.winnow.classifier.local.DeepExperimentKt")
    maxHeapSize = "2g"
}

tasks.register<JavaExec>("labCeilingExperiment") {
    group = "winnow"
    description = "Measures what caps a Lab model's accuracy on someone's own labels."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ericflo.winnow.classifier.local.LabCeilingExperimentKt")
    maxHeapSize = "3g"
    // -Ppart=two for part two (labels it's sure are wrong, parts of words, more labels).
    providers.gradleProperty("part").orNull?.let { systemProperty("part", it) }
}
