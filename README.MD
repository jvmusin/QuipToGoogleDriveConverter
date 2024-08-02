# Quip to Google Drive migration tool

This tool simplifies files migration from Quip to Google Drive.  
It downloads files from Quip,
uploads them back to Google Drive preserving hierarchy,
and then updates links to documents from Quip to newly uploaded documents to Google Drive.

## Preparation

To use this tool, you have to:

1. **!NOT REQUIRED ANYMORE! SKIP THIS STEP!** Run `_1_quipDownloadApi` script to download and build Quip API Client (you
   should have maven installed locally)
2. Create a personal access token for Quip following [this instruction](https://quip.com/api/personal-token), and put
   the token as plain text into `src/main/resources/quip_access_token.txt` file
3. Create credentials for Google Drive following
   the [instruction](https://developers.google.com/drive/api/quickstart/java), and put generated credentials file
   to `src/main/resources/credentials.json`
4. If you are going to upload files to a shared drive, run `_extra_driveListDrives`, find id of your drive, and put it
   under `driveId` field in `src/main/resources/settings.jsonc`
5. If you want to transfer files ownership or add author info in at the beginning of documents, get all Quip users from
   Quip SCIM API at https://quip.com/dev/scim/documentation/current#operation/get_users_v1  
   Put the resulting file in `quip_users_scim.json` in the root of the project.

## TLDR

You can ignore everything below this section if not interested in how it works.  
These are 4 steps to migrate all documents from Quip to Google Drive:

1. Go through the [Preparation](#preparation) section above
2. Run `_2_quipDownloadFiles` to download files from Quip
3. Run `_3_driveUploadFiles` to upload files to Google Drive
4. Run `_4_driveUpdateLinks` to update links in files from Quip to Google Drive

## Downloading files from Quip

### Shortcut

`./_2_quipDownloadFiles`

### How to

To download files from Quip, Run `QuipDownloadFiles`, it will download all files into `downloaded` folder in the root of
the project.  
This function will download files to `downloaded` directory under root directory.

**IMPORTANT**: To avoid

```
Exception in thread "main" java.lang.reflect.InaccessibleObjectException: Unable to make field private volatile java.lang.Object java.util.concurrent.atomic.AtomicReference.value accessible: module java.base does not "opens java.util.concurrent.atomic" to unnamed module @3f56875e
```

Add the following to the VM options in the Run Configuration to:

```
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED
```

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
This way, you can run `QuipDownloadFiles` multiple times and get more and more files downloaded.  
You can also run the script and let it work for a long time - Quip API client is designed to work with a backoff
strategy, so keeping the downloader overnight might be a good idea.

Please note that to continue to the uploading stage, you have to download all files from Quip first.  
Otherwise, there will be no file `_folder.json` responsible for the folder data.  
This file is created only when the whole folder is downloaded (for every nested folder).

## Uploading files to Google Drive

### Shortcut

`./_3_driveUploadFiles`

### Google Drive Rate Limits

Google Drive has decent limits, allowing to upload files non-stop in one thread.  
But even if the uploading fails because of Rate Limits, there is a backoff strategy to repeat the uploading if it fails.

### How to

To upload files to Google Drive, run `DriveUploadFiles`.  
This function will create a folder on your Google Drive and upload all the files to that folder, preserving hierarchy.  
Files will be uploaded to a folder named "Quip" by default.  
You can change it in `src/main/resources/settings.jsonc`.

If you want to re-upload the whole thing to Drive, first remove the Quip folder from Drive, and then from Bin.  
Run `_extra_driveResetInfo`, and then you are good to re-run uploading.

## Updating links

### Shortcut

`./_4_driveUpdateLinks`

Files on Quip may have links to other files on Quip.  
Keeping them is not ideal, and you can replace such links in all `docx` (Documents) and `xlsx` (Spreadsheets) files.

### How to

To update links, run `DriveUpdateLinks`.  
It will check links in all files, and if it finds links to update, it updates them and uploads an updated file on Google
Drive.  
If your other files are not in `docs.google.com`, but under some specific domain, then open any file on drive, check the
domain, and replace `driveDomain` field in `src/main/resources/settings.jsonc` with a correct one.  
If you want to additionally include author name at the beginning of documents, set `"includeAuthorName": true`
in `src/resources/settings.jsonc`.

### How it works

When you upload files to Google Drive, the script saves file's Google Drive id in its `.json` file.  
When you run `DriveUpdateLinks`, it skims through all the links in files and replaces links if they lead to an uploaded
file.  
Then the updated file is saved under `{QUIP_ID}_updated.(extension)`.  
Then the file on Google Drive is updated by this `_updated` copy.

## Listing private files

### Shortcut

`_extra_quipListPrivateFiles`

### How to

You can list all files that exist on Quip, but not visible to all members of the quip folder we are downloading.  
For this to work, you need Admins Quip API key.  
Run `_extra_quipListPrivateFiles` to do that. It will log all these files into console and to `private_files.txt` file.

## Transferring ownership on Google Drive

### How to

First, get all Quip users from Quip SCIM API - https://quip.com/dev/scim/documentation/current#operation/get_users_v1  
Put the resulting file in `quip_users_scim.json` in the root of the project.  
Then, you can run `_extra_quipTransferOwnership`.  
If the execution fails because you don't have some users info, you have to add this user manually to a
file `quip_email_extra.json` and repeat:

```json
{
  "QUIP_USER_ID": "some.email@com.org",
  "QUIP_USER_ID_2": "some-other-email@google.com",
  "QUIP_USER_TO_IGNORE": ""
} 
```

## Commentaries

### Shortcuts

`./_extra_quipDownloadComments`
`./_extra_driveUploadComments`

### How to

To download comments from Quip, run `./_extra_quipDownloadComments`.  
This script will download comments and save them in a Markdown file.

Then upload comments to Google Drive using `./_extra_driveUploadComments`.  
This script will save comments file under the same directory as the original file with a name `ORIG_FILE [comments]`.
