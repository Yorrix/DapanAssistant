# Optional components

The main APK contains scanner code and native runtime, but neither its model nor the ECDICT database. Both data resources download inside the app without installing another APK.

- **Dictionary**: `payloads/ecdict-v1.db.gz`, downloaded with a base-APK-pinned SHA-256 over HTTPS. Its decompressed size, SHA-256 and SQLite structure are verified before atomic installation. Existing dictionaries, personal known words and vocabulary history are preserved.
- **Scanner model**: content-addressed `docquadnet256-<hash>.ort`, 13,404,960 bytes. SHA and size are checked before atomic storage in `noBackupFilesDir/scanner-models/docquadnet256.ort`. The detector checks both again before inference. First download proceeds directly to the camera; later launches work offline. Deleting the model preserves all scanned documents. CameraX, OpenCV and ONNX Runtime remain in the signed main app; no executable code is downloaded.
- **Timetable**: uses Android's system WebView. No browser runtime is downloaded or included.

Run `build-local.ps1` to generate data hashes, then build and test the app. The processing module is compiled from source and excludes only `docquad/*.ort` from assets, retaining all licenses and model metadata. Keep the original model source for reproducible builds. Content-addressed URLs must remain immutable; legacy APK payloads on the server may remain for earlier builds, but current apps never download or launch them. Current artifacts use the existing development signing identity.

`catalogue.json` is the build manifest. `scripts/component-catalogue.py` generates `ComponentCatalog.kt`. Keep payloads outside `app/src/main/assets`; otherwise AAPT may expand `.gz` assets into the base APK again.

Downloads are opt-in and cancellable. Keep ECDICT and scanner license/attribution files distributed with their respective packages. Deleting a component never deletes the user's exported documents or word history.
