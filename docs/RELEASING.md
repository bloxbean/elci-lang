# Releasing Elci

The release workflow runs when a `v*` tag is pushed. `gradle.properties` is the source of the Elci version, embedded in the CLI and ABI metadata. A tag must match it exactly: `version=0.1.0-pre1` requires `v0.1.0-pre1`. Tags containing a prerelease suffix produce GitHub prereleases.

Keep `julcVersion` and `julcRevision` aligned with the backend used to test the release. `julcVersion` is a Julc snapshot from the BloxBean Maven repository (`https://repo.bloxbean.org/maven/snapshots`), which Gradle resolves like any dependency; the development backend is not yet published to Maven Central. `julcRevision` records the full Julc commit SHA that snapshot was built from.

## Tagging a release

Releases follow the release-ops PR-gated flow (`docs/03-workflows.md` §4). Nobody pushes `v*` tags by hand:

1. Open a PR that changes `version` in `gradle.properties` to the release version, for example `0.1.0-pre1`. `.github/CODEOWNERS` requests review from the release owner.
2. When it merges to `main` (or a `release/**` branch), `.github/workflows/tag-release.yml` runs. Its `authorize` job runs in the `release` environment, which is the approval gate once that environment has required reviewers. Its `tag` job, in `release-staging`, mints a `bloxbean-release-tagger` App token and pushes `v<version>` on the merged commit. A tag pushed with the default `GITHUB_TOKEN` would not trigger the release workflow.
3. The tag starts `.github/workflows/release.yml`.
4. Open a second PR that sets `version` back to the next development version, for example `0.1.0-dev`.

`tag-release.yml` skips `SNAPSHOT` and `-dev` versions and tags that already exist, so a Julc pin bump in `gradle.properties` doesn't tag anything. To tag a version whose run was skipped, rejected or expired, start the workflow by hand; it tags the branch's current commit:

```sh
gh workflow run tag-release.yml --repo bloxbean/elci-lang --ref main
```

The `release-staging` environment needs the secrets `RELEASE_APP_ID` and `RELEASE_APP_PRIVATE_KEY`, and the App must be installed on the repository (release-ops `docs/04-github-settings.md` §1).

## Release builds

GitHub Actions uses GraalVM **25.3.4.1**, the published patch release in the requested 25.3.4 series. Each runner executes the test suite, builds the native CLI and runs `.github/scripts/cli-smoke.sh` on it: the embedded version, `init`, `check`, `build`, `eval`, `apply` and `test` on the examples. The snapshot distributions run the same script.

Release archives contain the `elci` executable and documentation:

- `elci-<version>-linux-x64.tar.gz`
- `elci-<version>-linux-arm64.tar.gz`
- `elci-<version>-macos-arm64.tar.gz`

Native compilation uses `--no-fallback` and `-H:+ReportExceptionStackTraces`, matching Julc CLI. It produces an executable. GraalVM 25.3.4.1 reports `--no-fallback` as deprecated with no effect; the flag is retained for consistency with Julc CLI, not as a static-linking setting.

Linux binaries are built on Ubuntu 22.04 and require a compatible glibc-based system; they are not static binaries. macOS binaries are unsigned and not notarized. Intel macOS and Windows binaries are not produced by this workflow; Windows x64 is built only as a snapshot distribution for now (below).

After all three builds pass, a separate job uses `gh release` to upload the archives and `SHA256SUMS`, then publish the release. Only that job has repository write permission. Failed uploads leave a draft that the job can retry. It refuses to overwrite an already published release; use a new version/tag for replacement binaries.

To install, download the archive for your platform, verify its checksum against `SHA256SUMS`, extract it, and place `elci` on your PATH. No Java installation is required for the native executable.

Fully static musl builds are deferred to a later release. GraalVM 25.3.4.1 does not support musl on Linux ARM64, and the backend’s dynamically loaded BLS library needs a compatible implementation and runtime tests before we can promise no shared-library dependencies.

## Alignment with Julc CLI

Both release workflows use Ubuntu 22.04 for Linux x64/ARM64 and `gradle/actions/setup-gradle@v4`. Elci uses macOS 15 ARM64 for Apple Silicon rather than Julc’s macOS 14 runner. Both build native executables with `--no-fallback` and `-H:+ReportExceptionStackTraces` and include service-provider resources. Julc CLI has no `native-image.properties`; its options live in Gradle, as do Elci’s.

Elci retains its exact GraalVM 25.3.4.1 pin, Gradle-sourced release version, frontend-specific reachability metadata, and compiler smoke tests. Julc’s scaffolding, verification, and CLI-specific reflection entries do not apply to Elci. Windows packaging remains outside this release’s platform set. These settings do not establish native BLS execution support, which is deferred for the developer preview.

## Snapshots

`.github/workflows/bloxbean-snapshot.yml` publishes `org.elci:elci-lang` (jar, sources, javadoc, POM and Gradle module metadata) to the BloxBean Maven repository, `https://repo.bloxbean.org/maven/snapshots`. It follows the release-ops standard (`docs/12-bloxbean-maven-repository.md`), simplified for now:

- **Trigger:** manual only (`workflow_dispatch`) on `main` or `release/**`, with no approval step. It only adds a version; it never replaces or deletes one.
- **Version:** one per commit, `<version>-<sha7>-SNAPSHOT` (for example `0.1.0-dev-1a2b3c4-SNAPSHOT`), passed with `-Pversion`. `gradle.properties` keeps its plain version, so ordinary builds and the ABI manifests they write don't change from commit to commit.
- **Tests:** the workflow runs `./gradlew test` itself rather than requiring a green `build.yml` run for the commit.
- **Publication:** the artifact's version list is seeded from the bucket, so the new version is appended. The staged repository is checked with `verify-maven-staging.py`, uploaded, compared byte for byte through the public URL, and resolved by a throwaway Gradle consumer.
- **Scripts:** `.github/scripts/bloxbean-maven.sh` and `.github/scripts/verify-maven-staging.py` are unchanged copies of the release-ops templates. Update them from there, not here.

The workflow needs these settings on the `release-staging` environment: deployment branches `main` and `release/**`, the secrets `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY`, and the variables `R2_BUCKET` and `R2_ENDPOINT`, as for Julc (release-ops `docs/05-secrets.md` §8).

Not yet adopted from the standard:

- an approval gate for the Maven snapshot;
- the snapshot cleanup workflow;
- the "Development snapshot" GitHub release.

Start a run with:

```sh
gh workflow run bloxbean-snapshot.yml --repo bloxbean/elci-lang --ref main
```

### Snapshot distributions

`.github/workflows/bloxbean-snapshot-dist.yml` publishes the CLI of a commit to `https://repo.bloxbean.org/dist/snapshots/elci/<version>/`, following the release-ops standard (`docs/12-bloxbean-maven-repository.md`, Snapshot distributions). The version is the Maven snapshot's, `<version>-<sha7>-SNAPSHOT`. Each version directory holds:

- `elci-<version>.zip`: the JVM distribution (`distZip`), with `bin/elci` and `bin/elci.bat`, for any OS with Java 25;
- `elci-<version>-linux-x64.tar.gz`, `elci-<version>-linux-arm64.tar.gz` and `elci-<version>-macos-arm64.tar.gz`: the native CLI, laid out as the release archives;
- `elci-<version>-windows-x64.zip`: the native CLI for Windows, `elci.exe`;
- `SHA256SUMS` and `manifest.json`.

`https://repo.bloxbean.org/dist/snapshots/elci/latest.json` names the most recently published version. The bucket expires snapshot distributions after 30 days.

- **Trigger:** manual only (`workflow_dispatch`) on `main` or `release/**`.
- **Gates:** the commit must have a green `build.yml` push run (`require-green-ci.sh`), so the tests are not repeated. `.github/workflows/dist-build.yml` then builds the five files and smoke-tests each one with `cli-smoke.sh`, the JVM zip on Linux and each native CLI on its own platform. A release owner approves in the `release` environment before anything is uploaded; that environment needs required reviewers, or the gate passes without one.
- **Publication:** in `release-staging`, the job checks that exactly the five expected files arrived, then `bloxbean-dist.sh` writes `SHA256SUMS` and `manifest.json`, uploads the files with the manifest last, updates `latest.json` and checks the public URLs. A published version is never overwritten.
- **Scripts:** `.github/scripts/bloxbean-dist.sh` and `.github/scripts/require-green-ci.sh` are unchanged copies of the release-ops templates.

A pull request that changes `dist-build.yml`, `cli-smoke.sh` or the native-image metadata runs `dist-build.yml` as a check, without publishing. Start a publication with:

```sh
gh workflow run bloxbean-snapshot-dist.yml --repo bloxbean/elci-lang --ref main
```

## Documentation site

`.github/workflows/docs-deploy.yml` builds the site in `www/` (release-ops `docs/03-workflows.md` §7). A pull request that touches the site, or the repository files it reads, only builds it. A push to `main`, or a manual run on `main`, also pushes the built site to the `gh-pages` branch. The live site changes only after GitHub's `pages-build-deployment` run for that push is approved in the `github-pages` environment.

The site links pages from the root, so it is served at the root of its own domain. Set that domain as the repository variable `DOCS_DOMAIN`. The workflow builds with `ELCI_SITE_URL=https://$DOCS_DOMAIN` and writes it to the `CNAME` file. Pages must deploy from the `gh-pages` branch.
