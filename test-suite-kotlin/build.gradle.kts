plugins {
    id("io.micronaut.build.internal.cdi-test-suite")
    id("org.jetbrains.kotlin.jvm")
    id("io.micronaut.build.internal.kotlin-base")
    id("io.micronaut.build.internal.kotlin-ksp")
}

// The language model an extension reads is built on Micronaut's AST alone, so it is the same model in a Kotlin
// compilation: the extension and the processor run under KSP here, on a Kotlin class.
dependencies {
    kspTest(platform(libs.micronaut.core))
    kspTest(projects.micronautCdiProcessor)
    kspTest(mn.micronaut.inject.kotlin)
    // a build compatible extension runs while the classes it enhances are compiled, so it goes here
    kspTest(projects.testSuiteExtension)

    testImplementation(platform(libs.micronaut.core))
    testImplementation(projects.micronautCdi)
    testImplementation(projects.testSuiteExtension)
    testImplementation(mn.micronaut.core)
    testImplementation(mn.micronaut.inject)
    testImplementation(mn.micronaut.context)
    testImplementation(libs.managed.jakarta.cdi.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
