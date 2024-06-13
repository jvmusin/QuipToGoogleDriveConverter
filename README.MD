# Quip to Google Drive migration tool

## Preparation

To use this tool, you have to:

1. Run `downloadQuipApi` script to download and build Quip API Client
2. Create a personal access token for Quip following [this instruction](https://quip.com/api/personal-token), and put
   the token as plain text into `src/main/resources/quip_access_token.txt` file
3. Create credentials for Google Drive following
   the [instruction](https://developers.google.com/drive/api/quickstart/java), and put generated credentials file
   to `src/main/resources/credentials.json`

## Downloading files from Quip

### How to

To download files from Quip, Run the `QuipDownloader` function, it will download all files into `downloaded` folder
in the root of the project.  
This function will download files to `downloaded` directory under root directory.

**IMPORTANT**: To avoid

```
Exception in thread "main" java.lang.reflect.InaccessibleObjectException: Unable to make field private volatile java.lang.Object java.util.concurrent.atomic.AtomicReference.value accessible: module java.base does not "opens java.util.concurrent.atomic" to unnamed module @3f56875e
```

Add the following to the VM options in the Run Configuration to:

```
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED
```

TODO: Add the above to gradle.kts

### Downloaded Files Structure

Each file is named as its id on Quip, and accompanied by a file with the same name and an extension `.json` with all
info available through Quip API for the folder. This json includes things such as title, owners, last edit date,
access levels, etc.  
For example, the file may be called `ABACABA.docx`, and there will be a file `ABACABA.json`.  
This file can also contain some additional data to simplify the whole tool.

Similarly, all folders are named identical to their id on Quip, and inside every folder there is a file
named `_folder.json`.

### Quip Rate Limits

Quip has quite tiny rate limits on QPI requests - 50 req per minute / 750 req per hour.

It might be impossible to download all the files at the same time, hence the tool supports skipping already downloaded
files.  
This way, you can run `QuipDownloader` multiple times and get more and more files downloaded.  
You can also run the script and let it work for a long time - Quip API client is designed to work with a backoff
strategy, so keeping the downloader overnight might be a good idea.

Please note that to continue to the uploading stage, you have to download all files from Quip first.  
Otherwise, there will be no file `_folder.json` responsible for the folder data.  
This file is created only when the whole folder is downloaded (for every nested folder).

## Uploading files to Google Drive

### Google Drive Rate Limits

Google Drive has decent limits, allowing to upload files non-stop in one thread.  
But even if the uploading fails because of Rate Limits, there is a backoff strategy to repeat the uploading if it fails.

### How to

To upload files to Google Drive, run the `DriveUploader` function.  
This function will create a folder on your Google Drive and upload all the files to that folder, preserving hierarchy.

## Updating links

Files on Quip may have links to other files on Quip.  
Keeping them is not ideal, and you can replace such links in all `docx` (Documents) and `xlsx` (Spreadsheets) files.

### How to

To update links, run `DriveLinksUpdater`.  
It will check links in all files, and if it finds links to update, it updates them and uploads an updated file on Google
Drive.  
If your other files are not in `docs.google.com`, but under some specific domain, then open any file on drive, check the
domain, and replace `const val DRIVE_DOMAIN = "docs.google.com"` in `DriveLinksUpdater` with a correct one.

### How it works

When you upload files to Google Drive, the script saves file's Google Drive id in its `.json` file.  
When you run `DriveLinksUpdater`, it skims through all the links in files and replaces links if they lead to an uploaded
file.  
Then the updated file is saved under `QUIP_ID_updated.(extension)`.  
Then the file on Google Drive is updated by this `_updated` copy.
