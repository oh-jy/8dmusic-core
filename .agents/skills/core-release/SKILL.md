---
name: core-release
description: Prepare, verify, and publish an authorized 8D Space Audio Core Android release, including signatures, dependencies, privacy, and artifact provenance.
---

# Core release

1. Verify the requested repository, branch, worktree, working-tree changes and destination before changing Git state. Preserve separate repository histories; do not merge open-source-only changes into another branch without authorization.
2. Check `app/build.gradle.kts`, `AGENTS.md`, `README.md`, `PRIVACY.md`, `TERMS.md`, `THIRD-PARTY-SOURCES.md` and `THIRD-PARTY-NOTICES.md` against the actual features, permissions, data flows and resolved release dependencies. Review new assets and third-party rights; do not assert that a public stream is copyright-free.
3. Run tests and build the requested variant. For native changes, rebuild and inspect arm64/16 KB compatibility. Verify the exact APK package, version, ABI, hash and signing certificate. Keep keystores, credentials and user data out of the repository and output.
4. When device validation is requested or appropriate, install the exact artifact without removing data. Stop on signature mismatch or any failed step. Distinguish build, signing, install, functional and listening/visual validation in the handoff.
5. Before any public push, inspect the intended commit history and tracked files for sensitive material and confirm authorization for public assets. A scan reduces risk but does not prove absence of secrets. Provide applicable license and notice texts with the distributable.
6. Publish only to the authorized destination and verify the remote commit, release and downloadable assets. Do not claim completion if authentication, network, device, rights, or artifact verification blocks a required step.
