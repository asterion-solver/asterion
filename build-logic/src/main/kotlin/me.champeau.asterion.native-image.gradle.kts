import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

// The settings which the native images of Asterion share: its executables and its shared library
plugins {
    id("me.champeau.asterion.base")
    id("org.graalvm.buildtools.native")
}

val nativeImageBuilds = gradle.sharedServices.registerIfAbsent("nativeImageBuilds", NativeImageBuilds::class) {
    maxParallelUsages = 1
}
tasks.withType<BuildNativeImageTask>().configureEach {
    usesService(nativeImageBuilds)
}

// The version, which the images read from the resource asterion-version.txt
val writeVersion = tasks.register("writeVersion") {
    val version = providers.gradleProperty("version")
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        // only this file: no other one left by an earlier build
        val directory = output.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        directory.resolve("asterion-version.txt").writeText(version.get())
    }
}

sourceSets {
    main {
        resources.srcDir(writeVersion.map { it.outputs.files.singleFile })
    }
}

graalvmNative {
    toolchainDetection = false
    // None of our dependencies needs the shared reachability metadata
    metadataRepository {
        enabled = false
    }
    binaries.named("main") {
        buildArgs.addAll("-H:+UnlockExperimentalVMOptions", "-H:+ReportExceptionStackTraces")
        buildArgs.add("-H:IncludeResources=asterion-version.txt")
        // Native Image controls the optimization level of instrumented builds (nativeCompile --pgo-instrument)
        buildArgs.addAll(pgoInstrument.map { instrumented -> if (instrumented) listOf() else listOf("-O3") })
        // Portable binaries by default, -PnativeMarch=native to tune for the build machine
        buildArgs.add(providers.gradleProperty("nativeMarch").map { "-march=$it" }.orElse("-march=compatibility"))
        // Options to experiment with, separated by spaces: -PnativeExtraArgs="-H:-GenLoopSafepoints --gc=epsilon"
        buildArgs.addAll(providers.gradleProperty("nativeExtraArgs").map { it.split(" ").filter(String::isNotBlank) }.orElse(listOf()))
    }
}
