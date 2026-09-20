# Releasing Elci

The release workflow runs when a `v*` tag is pushed. `gradle.properties` is the source of the Elci version, embedded in the CLI and ABI metadata. A tag must match it exactly: `version=0.1.0-dev` requires `v0.1.0-dev`. Tags containing a prerelease suffix produce GitHub prereleases.

Before tagging, update and commit `version` in `gradle.properties`. Keep `julcVersion` and `julcRevision` aligned with the backend used to test the release. `julcRevision` pins the full Julc commit SHA; CI builds its required modules and publishes them to the runner's Maven local repository because the development backend is not yet published to Maven Central.

Push the version commit, then create and push the matching tag:

```sh
version=$(sed -n 's/^version=//p' gradle.properties)
git tag -a "v$version" -m "Elci $version"
git push origin "v$version"
```

GitHub Actions uses GraalVM **25.3.4.1**, the published patch release in the requested 25.3.4 series. Each runner executes the test suite, builds the native CLI, verifies its embedded version, compiles the vesting example, and evaluates the Elci library example.

Release archives contain the `elci` executable and documentation:

- `elci-<version>-linux-x64.tar.gz`
- `elci-<version>-linux-arm64.tar.gz`
- `elci-<version>-macos-arm64.tar.gz`

Linux binaries are built on Ubuntu 24.04 and require a compatible glibc-based system; they are not static binaries. macOS binaries are unsigned and not notarized. Intel macOS and Windows binaries are not produced by this workflow.

After all three builds pass, a separate job uses `gh release` to upload the archives and `SHA256SUMS`, then publish the release. Only that job has repository write permission. Failed uploads leave a draft that the job can retry. It refuses to overwrite an already published release; use a new version/tag for replacement binaries.

To install, download the archive for your platform, verify its checksum against `SHA256SUMS`, extract it, and place `elci` on your PATH. No Java installation is required for the native executable.
