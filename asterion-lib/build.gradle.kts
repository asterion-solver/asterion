plugins {
    id("me.champeau.asterion.native-image")
}

description = "Asterion Solver: a shared library, which programs in C, C++, Python... call to solve images in memory"

dependencies {
    implementation(projects.asterionCore)
    // the API of native images, which is part of them: only needed to compile
    compileOnly(libs.graalvm.nativeimage)
    testImplementation(testFixtures(projects.asterionCore))
}

graalvmNative {
    binaries.named("main") {
        // libasterion.so, libasterion.dylib or libasterion.dll, with the headers of its functions (libasterion.h)
        sharedLibrary = true
        imageName = "libasterion"
        // the library reads and writes the memory of the program which calls it, see NativeLibrary.memory
        buildArgs.add("--enable-native-access=ALL-UNNAMED")
        // Creating an isolate would otherwise set the locale of the whole process from the environment,
        // with setlocale(LC_ALL, ""), changing how the program prints and parses numbers: Asterion
        // doesn't depend on the locale anyway
        buildArgs.add("-H:-UseSystemLocale")
        // On macOS, programs look for a library where its name says: GraalVM names it after the path where it's built,
        // and @rpath makes them look in the directories they record with -Wl,-rpath, as on Linux
        if (System.getProperty("os.name").startsWith("Mac")) {
            buildArgs.add("-H:NativeLinkerOption=-Wl,-install_name,@rpath/libasterion.dylib")
        }
        // An instrumented library writes its profile when its isolate is torn down, see update-library-pgo.sh
        buildArgs.addAll(pgoInstrument.map { instrumented -> if (instrumented) listOf("-H:+ProfilingEnableProfileDumpHooks") else listOf() })
    }
}

// The library, its generated headers, and the header which documents it, side by side: everything native-image
// writes, such as the import library on Windows, or the libraries of the JDK which some features need
val nativeLibrary = tasks.register<Sync>("nativeLibrary") {
    description = "Gathers the shared library, the files it needs, and its headers"
    from(tasks.nativeCompile.flatMap { it.outputDirectory }) {
        // left there by nativeCompile --pgo-instrument
        exclude("*-instrumented*")
    }
    from(layout.projectDirectory.dir("src/main/c"))
    from(layout.projectDirectory.file("README.md"))
    into(layout.buildDirectory.dir("native-library"))
}

// The library is released apart from the executables, in an archive of its own for every platform:
// asterion-library-<version>.zip, see .github/workflows/release.yml
val libraryName = providers.gradleProperty("executableName").map { "$it-library" }
val platform = providers.nativePlatform()

val nativeLibraryZip = tasks.register<Zip>("nativeLibraryZip") {
    description = "Packages the library of the current platform, which nativeLibraryBundle gathers with the other ones"
    group = "distribution"
    val version = project.version.toString()
    archiveFileName = libraryName.zip(platform) { name, os -> "$name-$version-$os.zip" }
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from(nativeLibrary) {
        // in the bundle once, for every platform
        exclude("README.md")
        into(platform)
    }
}

// what reads the archives of the platforms, for nativeLibraryBundle: zipTree, a function of the script, can't be
// serialized by the configuration cache
interface ArchiveServices {
    @get:Inject
    val archives: ArchiveOperations
}
val archives = objects.newInstance<ArchiveServices>().archives

tasks.register<Zip>("nativeLibraryBundle") {
    description = "Packages the libraries of every platform, which CI builds with nativeLibraryZip, from the artifacts directory"
    group = "distribution"
    val version = project.version.toString()
    // a local value: the configuration cache can't serialize the variables of the script
    val archives = archives
    // the platforms by name: a pattern such as *-1.0.0-* would also take the archives of 1.0.0-rc.1
    val parts = rootProject.layout.projectDirectory.dir("artifacts").asFileTree.matching {
        listOf("linux", "osx", "windows").forEach { include("*-library-$version-$it-*.zip") }
    }
    inputs.files(parts)
    archiveFileName = libraryName.map { "$it-$version.zip" }
    destinationDirectory = rootProject.layout.projectDirectory.dir("artifacts")
    into(libraryName.map { "$it-$version" }) {
        from(parts.elements.map { files -> files.map { archives.zipTree(it) } })
        from(layout.projectDirectory.file("README.md"))
        from(rootProject.layout.projectDirectory.file("LICENSE.txt"))
        from(rootProject.layout.projectDirectory.dir("third-party")) {
            into("third-party")
        }
    }
    doFirst {
        require(!parts.isEmpty) { "No library in the artifacts directory: the release workflow downloads them there" }
    }
}

// The programs in C which call the library, see NativeLibraryTest: compiled with Visual C++ on Windows, which
// native-image requires there anyway, and with the C compiler of the system elsewhere
val windows = System.getProperty("os.name").startsWith("Windows")
val cTestDirectory = layout.buildDirectory.dir("c-test")
val compileCTests = listOf("solve", "catalogs").map { name ->
    tasks.register<Exec>("compileCTest" + name.replaceFirstChar(Char::uppercase)) {
        description = "Compiles the C program $name, which tests the library"
        // local values: the configuration cache can't serialize the variables of the script
        val windows = windows
        val library = nativeLibrary.map { it.destinationDir }
        val source = layout.projectDirectory.file("src/test/c/$name.c")
        val output = cTestDirectory.map { it.file(if (windows) "$name.exe" else name) }
        // cl, in the environment of Visual C++ when the build doesn't run in it, like native-image
        val compiler = cTestDirectory.map { it.file("cl-$name.bat") }
        inputs.files(nativeLibrary)
        inputs.file(source)
        outputs.file(output)
        executable = if (windows) compiler.get().asFile.absolutePath else "cc"
        argumentProviders.add(CommandLineArgumentProvider {
            val dir = library.get().absolutePath
            val program = output.get().asFile.absolutePath
            if (windows) {
                listOf("/nologo", "/O2", "/W3", "/WX", "/I", dir, source.asFile.absolutePath, "/Fe:$program", "/Fo:$program.obj",
                    "/link", "/LIBPATH:$dir", "libasterion.lib")
            } else {
                listOf("-O2", "-Wall", "-Wextra", "-Wpedantic", "-Werror", "-I", dir, source.asFile.absolutePath, "-L", dir, "-lasterion",
                    "-lm", "-Wl,-rpath,$dir", "-o", program)
            }
        })
        doFirst {
            output.get().asFile.parentFile.mkdirs()
            if (windows) {
                val setup = vcvarsall()?.let { "where cl >nul 2>nul || call \"$it\" x64 >nul || exit /b 1\r\n" } ?: ""
                compiler.get().asFile.writeText("@echo off\r\n${setup}cl %*\r\n")
            }
        }
    }
}

// The programs in C and Python which use the native library, apart from the tests of the JVM, which don't build it
val nativeLibraryTest = tasks.register<Test>("nativeLibraryTest") {
    description = "Runs the programs in C and Python which use the native library"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("*.NativeLibraryTest")
    }
    // the library, which the programs load: it changes with its build options without the programs changing
    inputs.files(nativeLibrary).withPropertyName("library")
    inputs.files(compileCTests).withPropertyName("programs")
    inputs.dir("src/test/python").withPropertyName("python")
    val programs = cTestDirectory.map { it.asFile.absolutePath }
    val library = nativeLibrary.map { it.destinationDir.absolutePath }
    val python = layout.projectDirectory.dir("src/test/python").asFile.absolutePath
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dasterion.cTests=" + programs.get(), "-Dasterion.library=" + library.get(), "-Dasterion.python=" + python)
    })
}

tasks.test {
    filter {
        excludeTestsMatching("*.NativeLibraryTest")
    }
}

tasks.check {
    dependsOn(nativeLibraryTest)
}
// The workload of the profile-guided optimization of the library, see update-library-pgo.sh: the instrumented
// library writes its profile to build/pgo
tasks.register<JavaExec>("pgoWorkload") {
    description = "Solves images with the instrumented library: --args=\"LIBRARY CATALOGS IMAGE...\""
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.champeau.asterion.lib.PgoWorkload"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val directory = layout.buildDirectory.dir("pgo")
    workingDir(directory)
    doFirst { directory.get().asFile.mkdirs() }
}
