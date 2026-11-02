import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

plugins {
    id("me.champeau.asterion.native-image")
    application
}

// the name of the executable of the command line tool: other modules set their own
val executableName = providers.gradleProperty("executableName").get()

application {
    applicationName = executableName
}

// The version of the project, and the name of the executable of the command line tool, are made
// available to the applications as resources
// The name of the executable, which the images read from the resource asterion-executable.txt
val writeExecutableName = tasks.register("writeExecutableName") {
    val name = executableName
    val output = layout.buildDirectory.dir("generated/executable")
    inputs.property("executableName", name)
    outputs.dir(output)
    doLast {
        // only this file: no other one left by an earlier build
        val directory = output.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        directory.resolve("asterion-executable.txt").writeText(name)
    }
}

sourceSets {
    main {
        resources.srcDir(writeExecutableName.map { it.outputs.files.singleFile })
    }
}

graalvmNative {
    binaries.named("main") {
        imageName = executableName
        buildArgs.add("--no-fallback")
        buildArgs.add("-H:IncludeResources=asterion-executable.txt")
    }
}

// The platform, named the way JReleaser does: linux-x86_64, osx-aarch_64, windows-x86_64...
val platform = providers.nativePlatform()

// The native executable of this module and the notices of its third-party data, which the archive
// of another module can bundle: see the `bundledExecutables` configuration
val nativeBundleCategory = objects.named<Category>("native-bundle")
val nativeBundle = tasks.register<Sync>("nativeBundle") {
    description = "Gathers the native executable and the notices of this module, for the archive of another one"
    from(tasks.named<BuildNativeImageTask>("nativeCompile").flatMap { it.outputDirectory }) {
        // left there by nativeCompile --pgo-instrument
        exclude("*-instrumented*")
    }
    from(layout.projectDirectory.dir("third-party")) {
        into("third-party")
    }
    into(layout.buildDirectory.dir("native-bundle"))
}
configurations.consumable("nativeBundleElements") {
    attributes.attribute(Category.CATEGORY_ATTRIBUTE, nativeBundleCategory)
    outgoing.artifact(nativeBundle)
}

// The executables of other modules which the archive of this one contains
val bundledExecutables = configurations.dependencyScope("bundledExecutables")
val bundledExecutablesPath = configurations.resolvable("bundledExecutablesPath") {
    extendsFrom(bundledExecutables.get())
    attributes.attribute(Category.CATEGORY_ATTRIBUTE, nativeBundleCategory)
}

tasks.register<Zip>("nativeZip") {
    description = "Packages the native executable for the current platform, with the bundled ones"
    group = "distribution"
    val version = project.version.toString()
    // the name of the native image of this module, which the configuration cache can serialize
    val executable = graalvmNative.binaries.named("main").flatMap { it.imageName }
    val distributionName = executable.zip(platform) { name, os -> "$name-$version-$os" }
    archiveFileName = distributionName.map { "$it.zip" }
    destinationDirectory = layout.buildDirectory.dir("distributions")
    into(distributionName) {
        from(tasks.named<BuildNativeImageTask>("nativeCompile").flatMap { it.outputDirectory }) {
            // left there by nativeCompile --pgo-instrument
            exclude("*-instrumented*")
            eachFile {
                if (name == executable.get() || name == executable.get() + ".exe") {
                    permissions {
                        unix("rwxr-xr-x")
                    }
                }
            }
        }
        // the executables of other modules, next to this one, and the notices of their data
        from(bundledExecutablesPath) {
            eachFile {
                if (!sourcePath.startsWith("third-party/")) {
                    permissions {
                        unix("rwxr-xr-x")
                    }
                }
            }
        }
        from(rootProject.layout.projectDirectory.files("LICENSE.txt", "README.md"))
        // the notices of third-party software, of the project and of this module
        from(rootProject.layout.projectDirectory.dir("third-party")) {
            into("third-party")
        }
        from(layout.projectDirectory.dir("third-party")) {
            into("third-party")
        }
    }
}
