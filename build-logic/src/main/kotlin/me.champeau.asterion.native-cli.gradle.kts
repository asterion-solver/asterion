import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

plugins {
    id("me.champeau.asterion.base")
    application
    id("org.graalvm.buildtools.native")
}

val executableName = providers.gradleProperty("executableName").get()

application {
    applicationName = executableName
}

// The version of the project is made available to the application as a resource
val versionFile = layout.buildDirectory.file("generated/version/asterion-version.txt")
val writeVersion = tasks.register("writeVersion") {
    val version = providers.gradleProperty("version")
    val output = versionFile
    inputs.property("version", version)
    outputs.file(output)
    doLast {
        output.get().asFile.writeText(version.get())
    }
}

sourceSets {
    main {
        resources.srcDir(writeVersion.map { it.outputs.files.singleFile.parentFile })
    }
}

graalvmNative {
    toolchainDetection = false
    // None of our dependencies needs the shared reachability metadata
    metadataRepository {
        enabled = false
    }
    binaries.named("main") {
        imageName = executableName
        buildArgs.addAll("--no-fallback", "-H:+UnlockExperimentalVMOptions", "-H:+ReportExceptionStackTraces")
        // Native Image controls the optimization level of instrumented builds (nativeCompile --pgo-instrument)
        buildArgs.addAll(pgoInstrument.map { instrumented -> if (instrumented) listOf() else listOf("-O3") })
        buildArgs.add("-H:IncludeResources=asterion-version.txt")
        // Portable binaries by default, -PnativeMarch=native to tune for the build machine
        buildArgs.add(providers.gradleProperty("nativeMarch").map { "-march=$it" }.orElse("-march=compatibility"))
    }
}

// The platform, named the way JReleaser does: linux-x86_64, osx-aarch_64, windows-x86_64...
val platform = providers.systemProperty("os.name").zip(providers.systemProperty("os.arch")) { name, arch ->
    val os = name.lowercase().let {
        when {
            it.contains("win") -> "windows"
            it.contains("mac") || it.contains("darwin") -> "osx"
            else -> "linux"
        }
    }
    val cpu = when (arch.lowercase()) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch_64"
        else -> arch.lowercase()
    }
    "$os-$cpu"
}

tasks.register<Zip>("nativeZip") {
    description = "Packages the native executable for the current platform"
    group = "distribution"
    val version = project.version.toString()
    // a local copy, which the configuration cache can serialize
    val executable = executableName
    val distributionName = platform.map { "$executable-$version-$it" }
    archiveFileName = distributionName.map { "$it.zip" }
    destinationDirectory = layout.buildDirectory.dir("distributions")
    into(distributionName) {
        from(tasks.named<BuildNativeImageTask>("nativeCompile").flatMap { it.outputDirectory }) {
            // left there by nativeCompile --pgo-instrument
            exclude("*-instrumented*")
            filesMatching(listOf(executable, "$executable.exe")) {
                permissions {
                    unix("rwxr-xr-x")
                }
            }
        }
        from(rootProject.layout.projectDirectory.files("LICENSE.txt", "README.md"))
    }
}
