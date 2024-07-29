import org.jetbrains.kotlin.util.capitalizeDecapitalize.decapitalizeAsciiOnly

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

fun registerRunTask(mainClass: String) = tasks.register(mainClass.decapitalizeAsciiOnly()) {
    application.mainClass = "io.github.jvmusin.$mainClass"
    dependsOn(ApplicationPlugin.TASK_RUN_NAME)
}

registerRunTask("QuipDownloadFiles")
registerRunTask("QuipListPrivateFiles")
registerRunTask("QuipListDocumentAuthors")
registerRunTask("QuipTransferOwnership")
registerRunTask("DriveUploadFiles")
registerRunTask("DriveUpdateLinks")
registerRunTask("DriveListDrives")
registerRunTask("DriveResetInfo")

group = "io.github.jvmusin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("quip.jar"))

    implementation("com.google.api-client:google-api-client:2.6.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.36.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240628-2.0.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(11)
    compilerOptions {
        optIn = listOf("kotlin.io.path.ExperimentalPathApi")
    }
}
