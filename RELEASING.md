# Maven Central release

The Maven namespace `io.github.agent-virtual-runtime` is verified. Publishing is a separate operation: a version tag triggers GitHub Actions to upload a signed bundle to Central Portal, where a maintainer reviews and manually publishes it. Pushes to `main` only run the build workflow.

## One-time setup

1. Create the GitHub Actions Environment named `maven-central`. The publishing job explicitly references this environment; repository-level secrets are not required. Configure required reviewers and restrict deployment to release tags if the repository's plan permits it.
2. In [Central Portal](https://central.sonatype.com/usertoken), generate a publishing User Token for an account allowed to publish under `io.github.agent-virtual-runtime`. Store its generated username and password as **environment secrets** `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` in `maven-central`. These are token credentials, not your Portal login password.
3. Create a passphrase-protected GPG signing key if the project does not already have one. Publish its **public** key to a public key server as described in the [Central GPG guide](https://central.sonatype.org/publish/requirements/gpg/). Store the ASCII-armored **private** key in `GPG_PRIVATE_KEY` and its passphrase in `GPG_PASSPHRASE` as secrets in the same environment. Never commit either value or send them in chat.
4. Restrict who can create release tags in GitHub repository rules. The release workflow has read-only repository permissions, but anyone who can push a matching tag can trigger an upload if the environment's protection rules allow it.

The `central-release` Maven profile generates source and Javadoc JARs, signs artifacts, and uses Sonatype's Central Publishing Maven Plugin. It does not auto-publish. The release workflow excludes `avr-examples`; it is a runnable example, not a library dependency for consumers.

## Each release

1. Update the parent and all module POM versions, dependency examples, and changelog. Run `mvn clean verify`. Review the release changes and merge them to `main`.
2. Create a new `v<project.version>` tag on that exact commit. Never move or reuse an existing release tag; Maven Central releases are immutable.
3. Push the new tag. The workflow checks that the tag matches the Maven version, verifies all modules, then runs the `central-release` profile to upload a bundle. Do not push the tag until the four Actions secrets are configured.
4. Wait for the workflow to report a validated deployment. Open [Central Portal Deployments](https://central.sonatype.com/publishing/deployments), inspect the coordinates and artifacts, then click **Publish**. A validated upload is **not** yet available to users.
5. Once published, confirm that the artifacts are available from Maven Central and announce the version. Maven Central releases are immutable; fixes require a new version.

The workflow does not create tags, configure credentials, or click Publish. If validation fails, inspect the Portal errors and release a corrected bundle with a new version rather than overwriting a published coordinate.

For local packaging checks without uploading, run `mvn -Pcentral-release -Dgpg.skip=true -pl '!avr-examples' package`. Do not run `deploy` without intentional publishing credentials and a reviewed release commit.
