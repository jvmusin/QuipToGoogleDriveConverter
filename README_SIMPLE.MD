## Getting Quip Access Token

1. Go to https://quip.com/api/personal-token
2. Click `Get Pesonal Access Token`
3. Copy the key
4. Create a file `quip_access_token.txt` in the root folder of this project and paste the key there.

## Downloading a folder from Quip

1. In the browser, open the folder you want to download. For example, if you want to
   download https://yourcompany.quip.com/FXfSOQ5OETJH/Important-Docs paste its id `FXfSOQ5OETJH` in `settings.jsonc`
   file in the root of this project under `quipFolderId` key.
2. Run `./_1_quipDownloadAll` - it will download your folder to `downloaded` directory in the root of this project.

## Uploading the folder to Google Drive

1. Create credentials for Google Drive following
   the [instruction](https://developers.google.com/drive/api/quickstart/java), and put a generated credentials file as
   `credentials.json` in the root of this project.
2. Run `./_2_processDocuments` to add an author and update links in documents
3. Run `./_3_driveUploadAll` to upload files to the root of your Google Drive.
