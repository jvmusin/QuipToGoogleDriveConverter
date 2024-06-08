plugins {
    kotlin("jvm") version "2.0.0"
}

group = "io.github.jvmusin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("quip.jar"))

    implementation("com.google.api-client:google-api-client:2.0.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")
    implementation("com.google.apis:google-api-services-drive:v3-rev20220815-2.0.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
