# Submitting OpenBill to F-Droid

`app.openbill.android.yml` is the draft fdroiddata recipe. Descriptions, icon and screenshots
come from `fastlane/metadata/android/en-US/` in this repo.

Before submitting: the tag `v0.1.3-alpha` must exist on GitHub (the recipe builds that tag),
and `versionCode 4` / `versionName 0.1.3-alpha` in `androidApp/build.gradle.kts` must match.

1. Fork https://gitlab.com/fdroid/fdroiddata on GitLab and clone it.
2. `git checkout -b app.openbill.android`
3. Copy `docs/fdroid/app.openbill.android.yml` to `metadata/app.openbill.android.yml`.
4. Lint and build locally with fdroidserver (or the `registry.gitlab.com/fdroid/fdroidserver:buildserver` image):
   ```
   fdroid readmeta
   fdroid rewritemeta app.openbill.android
   fdroid lint app.openbill.android
   fdroid build -v -l app.openbill.android
   ```
5. Commit ("New app: OpenBill"), push, and open a merge request using the
   "App inclusion" template. Tick the checklist; note the app has no anti-features
   (no network permission, no trackers, no non-free deps).
6. Respond to reviewer feedback. Later releases are picked up by `AutoUpdateMode: Version`
   from new `v*-alpha` tags.

Notes: dependencies come only from Google Maven and Maven Central; the release build is
unsigned unless a keystore is configured, so F-Droid signs it itself.
