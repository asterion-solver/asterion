plugins {
    id("me.champeau.asterion.native-cli")
}

description = "Asterion Solver: command-line plate solver"

dependencies {
    implementation(projects.asterionCore)
    implementation(libs.picocli)
    implementation(libs.fits)
    implementation(libs.tamboui.toolkit)
    // the API of native images, which is part of them: only needed to compile
    compileOnly(libs.graalvm.nativeimage)
    // The terminal backend of TamboUI, found with a service loader. GraalVM 25 fails to instrument
    // its upcalls for profile-guided optimization: it is left out of the instrumented build, which
    // runs a non-interactive workload anyway (see update-pgo.sh)
    if (!providers.gradleProperty("withoutTerminalBackend").isPresent) {
        runtimeOnly(libs.tamboui.panama.backend)
    }
    annotationProcessor(libs.picocli.codegen)
}

tasks.compileJava {
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
}

application {
    mainClass = "me.champeau.asterion.cli.Main"
    // the TamboUI backend talks to the terminal through the foreign function API
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

graalvmNative {
    binaries.named("main") {
        // the TamboUI backend talks to the terminal through the foreign function API
        buildArgs.add("--enable-native-access=ALL-UNNAMED")
    }
}
