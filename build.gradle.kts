import org.jetbrains.kotlin.util.capitalizeDecapitalize.decapitalizeAsciiOnly

plugins {
    kotlin("jvm") version "2.0.20"
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

registerRunTask("QuipDownloadAll")
registerRunTask("ProcessDocuments")
registerRunTask("DriveUploadAll")
registerRunTask("DriveGenerateIds")
registerRunTask("QuipExtractAllUsers")
registerRunTask("CollectMigratedFilesTsv")

group = "io.github.jvmusin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("quip.jar"))

    implementation("com.google.api-client:google-api-client:2.7.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.36.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240809-2.0.0")
    implementation("org.jsoup:jsoup:1.18.1") // To extract comments text from HTML

    // Docx4j for altering docx files like adding comments, for example
    implementation("org.docx4j:docx4j-core:11.5.0")
    implementation("org.docx4j:docx4j-JAXB-ReferenceImpl:11.5.0")

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
