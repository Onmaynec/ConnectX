plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
