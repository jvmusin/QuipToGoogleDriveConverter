# Quip to Google Drive migration tool

This tool simplifies files migration from Quip to Google Drive.  
It downloads files from Quip,
uploads them back to Google Drive preserving hierarchy, comments, and adds authors info,
and then updates links to documents from Quip to newly uploaded documents to Google Drive.

## Acquiring credentials

To use this tool, you have to get Quip and Google Drive access keys:

1. Create a personal access token for Quip following [this instruction](https://quip.com/api/personal-token), and put
   the token as plain text into `quip_access_token.txt` file in the root of the project.
2. Create credentials for Google Drive following
   the [instruction](https://developers.google.com/drive/api/quickstart/java), and put a generated credentials file
   to `credentials.json` in the root of the project.

*Optional:  
If you want to update links to people in documents/mentions in comments,
get all Quip users from Quip SCIM API
using [SCIM API get_users method](https://quip.com/dev/scim/documentation/current#operation/get_users_v1)
and put the resulting file in `quip_users_scim.json` in the root of the project.

## Downloaded Files Structure

Each file is named as its id on Quip, and accompanied by a file with the same name and an extension `.json` with all
info available through Quip API for the folder. This json includes things such as title, owners, last edit date,
access levels, etc.  
For example, the file may be called `ABACABA.docx`, and there will be a file `ABACABA.json`.  
This file can also contain some additional data to simplify the whole tool.

Similarly, all folders are named identical to their id on Quip, and inside every folder there is a file
named `_folder.json`.

## Quip Rate Limits

Quip has quite tiny rate limits on API requests — 50 req per minute / 750 req per hour.  
If there are 3000 files in a directory, they will be downloaded in at least 4 hours.

## Standard transferring process

1. Download all the files from Quip via `./_1_quipDownloadAll`. Re-running this task is safe.
    1. Generate Google Drive ids `./_1a_driveGenerateIds`. This will improve links updating on the next step.
2. Process files (add comments, add author line, check correctness) via `./_2_processDocuments`.
3. Upload files to your own Google Drive via `./_3_driveUploadAll`.

The files will be downloaded to `downloaded` folder in the root of the project.  
All these tasks are safe to re-run.
Already downloaded/processed/uploaded files and folders will be skipped during subsequent runs.

## Downloading private folder

To download any particular folder, follow the next steps:

1. Open the folder on Quip website and copy the id from the address bar, for example
   for https://yourcompany.quip.com/FXfSOQ5OETJH/Important-Docs the id will be `FXfSOQ5OETJH`.
2. Open `settings.jsonc` file in the root directory of this project, and replace the value of `quipFolderId` to the id
   you got on step 1.
3. Proceed to the [standard transferring process](#standard-transferring-process).

## TODO

The following parts are upcoming

- Handle BLOB files
- Replace links from Quip folders to Drive folders
- Use authors' name and date of the comment in xlsx files
- Add an ability to scan the whole Quip root folder and find/download private files

If you want your private files to have as much information transferred as possible, please wait until the features above
are ready.
