# Quip to Google Drive migration tool

## Preparation

To use this tool, you have to:

1. Run `buildQuipApi` script to build Quip SDK
2. Create a personal access token for Quip following [this instruction](https://quip.com/api/personal-token), and put
   the token as plain text into `src/main/resources/quip_access_token.txt` file
3. Create credentials for Google Drive following
   the [instruction](https://developers.google.com/drive/api/quickstart/java), and put generated credentials file
   to `src/main/resources/credentials.json`

## Downloading files from Quip

### How to

To download files from Quip, Run the `Downloader.main` function, it will download all files into `downloaded` folder in
the root of the project.

IMPORTANT: Add following to the VM options in the Run Configuration:

```
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED
```

### Downloaded Files Structure

Each file is named as its id on Quip, and accompanied by a file ending with `_title.txt` with the file title inside.
For example, the file may be called `ABACABA.docx`, and there will be a file `ABACABA_title.docx` containing
text `Kotlin 2.0.20`.

Similarly, all folders are named identical to their id on Quip, and inside every folder there is a file
named `_title.txt`, which contains name of the folder.

If you have no access to some folder, then the downloaded folder will contain an empty file `_no_access.txt`.

### Quip Rate Limits

Quip has quite tiny rate limits on QPI requests - 50 req per minute / 750 req per hour.

It might be impossible to download all the files at the same time, hence the tool supports skipping already downloaded
files. This way, you can run the Downloader multiple times throughout the day and get more and more files downloaded.

To achieve this, the `_title.txt` file is saved on the disk only when all files in the folder are downloaded. This
approach allows to quickly understand if there are any non-downloaded files left in the folder.

## Uploading files to Google Drive

### How to

To upload files to Google Drive, run the `Uploader.main` function. This function will create a folder on your Google
Drive and upload all the files to that folder, preserving hierarchy.

### Google Drive Rate Limits

I haven't searched for Rate Limits for Google Drive, but I skip uploading existing files, so if you hit rate limits, you
can re-run the script later, and it will skip already uploaded files.
