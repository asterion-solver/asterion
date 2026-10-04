plugins {
    java
    id("com.diffplug.spotless")
}

group = "me.champeau.asterion"

val catalog = versionCatalogs.named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(catalog.findVersion("java").get().requiredVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(catalog.findVersion("junit").get().requiredVersion)
            targets.configureEach {
                testTask.configure {
                    maxHeapSize = "2g"
                }
            }
        }
    }
}

spotless {
    java {
        // the rules are in config/formatting: `./gradlew format` applies them, `./gradlew check` verifies them
        eclipse().configFile(rootProject.layout.projectDirectory.file("config/formatting/eclipse-java.xml"))
        licenseHeader(
            """
            /*
             * Copyright ${'$'}YEAR the original author or authors.
             *
             * Licensed under the Apache License, Version 2.0 (the "License");
             * you may not use this file except in compliance with the License.
             * You may obtain a copy of the License at
             *
             *     https://www.apache.org/licenses/LICENSE-2.0
             *
             * Unless required by applicable law or agreed to in writing, software
             * distributed under the License is distributed on an "AS IS" BASIS,
             * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
             * See the License for the specific language governing permissions and
             * limitations under the License.
             */
            """.trimIndent()
        )
    }
}

tasks.register("format") {
    description = "Formats the sources"
    group = "verification"
    dependsOn("spotlessApply")
}
