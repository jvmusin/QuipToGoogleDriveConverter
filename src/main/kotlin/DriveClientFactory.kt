package io.github.jvmusin

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.reader

object DriveClientFactory {
    private const val APPLICATION_NAME: String = "Quip to Google Drive Migration"
    private const val TOKENS_DIRECTORY_PATH: String = "tokens"
    private const val CREDENTIALS_FILE_PATH: String = "/credentials.json"
    private val JSON_FACTORY: JsonFactory = GsonFactory.getDefaultInstance()
    private val SCOPES: List<String> = listOf(DriveScopes.DRIVE)

    private fun getCredentials(httpTransport: NetHttpTransport?): Credential {
        // Load client secrets.
        val credentialsFile = Paths.get(CREDENTIALS_FILE_PATH)
        require(credentialsFile.exists()) {
            "Google Drive Credentials file does not exist at $credentialsFile"
        }
        val clientSecrets =
            GoogleClientSecrets.load(JSON_FACTORY, credentialsFile.reader())

        // Build flow and trigger user authorization request.
        val flow = GoogleAuthorizationCodeFlow.Builder(
            httpTransport, JSON_FACTORY, clientSecrets, SCOPES
        )
            .setDataStoreFactory(FileDataStoreFactory(File(TOKENS_DIRECTORY_PATH)))
            .setAccessType("offline")
            .build()
        val receiver = LocalServerReceiver.Builder().setPort(8888).build()
        val credential = AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
        //returns an authorized Credential object.
        return credential
    }

    fun createClient(): DriveClient {
        return DriveClient(createRawClient())
    }

    private fun createRawClient(): Drive {
        val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
        val client = Drive.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
            .setApplicationName(APPLICATION_NAME)
            .build()
        return client
    }
}
