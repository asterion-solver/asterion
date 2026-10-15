plugins {
    id("me.champeau.asterion.native-cli")
}

description = "Asterion Solver: web interface"

dependencies {
    implementation(projects.asterionCore)
}

application {
    mainClass = "me.champeau.asterion.web.WebServer"
    applicationName = "asterion-web"
}

tasks.jar {
    // the credits of the sky data of the charts
    from(layout.projectDirectory.dir("third-party")) {
        into("META-INF/third-party")
    }
}

graalvmNative {
    binaries.named("main") {
        imageName = "asterion-web"
    }
}

tasks.named("nativeZip") {
    // the web interface is distributed in the archive of the command line tool
    enabled = false
}
