plugins {
    kotlin("jvm") version "2.0.0"
    application
}

application {
    applicationDefaultJvmArgs = listOf(
        "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED"
    )
}

tasks.register("quipDownload") {
    application.mainClass = "io.github.jvmusin.QuipDownloader"
    dependsOn("run")
}

tasks.register("driveUpload") {
    application.mainClass = "io.github.jvmusin.DriveUploader"
    dependsOn("run")
}

tasks.register("driveUpdateLinks") {
    application.mainClass = "io.github.jvmusin.DriveLinksUpdater"
    dependsOn("run")
}

group = "io.github.jvmusin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("quip.jar"))

    implementation("com.google.api-client:google-api-client:2.6.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.36.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240521-2.0.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
