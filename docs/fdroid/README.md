# Submitting OpenBill to F-Droid

`com.zenithblue.openbill.yml` is the lint-clean fdroiddata recipe (fdroidserver `readmeta`, `lint`,
`rewritemeta` all pass). Descriptions, icon and screenshots come from
`fastlane/metadata/android/en-US/` in this repo.

Before submitting: tags `v0.2.0-alpha` and `v0.1.3-alpha` must exist on GitHub, and
`versionCode 5` / `versionName 0.2.0-alpha` in `androidApp/build.gradle.kts` must match the tag.

1. Sign in at https://gitlab.com and fork https://gitlab.com/fdroid/fdroiddata (keep the fork public).
2. `git clone https://gitlab.com/<you>/fdroiddata.git && cd fdroiddata`
3. `git checkout -b com.zenithblue.openbill` (never submit from `master`).
4. `cp <openbill>/docs/fdroid/com.zenithblue.openbill.yml metadata/`
5. Check with fdroidserver (`pip install fdroidserver`):
   ```
   fdroid readmeta
   fdroid rewritemeta com.zenithblue.openbill
   fdroid lint com.zenithblue.openbill
   fdroid build -v -l com.zenithblue.openbill   # needs the buildserver image / x86_64 (see notes)
   ```
6. `git add metadata/com.zenithblue.openbill.yml && git commit -m "New app: com.zenithblue.openbill"`, then
   `git push -u origin com.zenithblue.openbill`.
7. Open the MR against fdroid/fdroiddata `master`, title `New app: OpenBill`. The default template
   loads; fill the abstract and tick the checklist (no anti-features: no network permission,
   no trackers, no non-free deps).
8. Watch the MR pipeline (also under CI/CD in your fork): it runs lint, `checkupdates` and a build.
   Fix failures, push again, answer packager questions. Later `v*-alpha` tags are picked up by
   `AutoUpdateMode: Version`.

Notes:
- Category is `Finance Manager` (`Money` is not in fdroiddata `config/categories.yml`).
- Dependencies come only from Google Maven and Maven Central; F-Droid signs the APK itself.
  `Binaries:` / `AllowedAPKSigningKeys:` (reproducible builds) are deliberately omitted: no local
  F-Droid build could be compared with the GitHub release APK. Add them later once it matches.
- A local `fdroid build` from the pip package fails on missing `gradlew-fdroid` (buildserver-only);
  use the `registry.gitlab.com/fdroid/fdroidserver:buildserver` image on x86_64.
