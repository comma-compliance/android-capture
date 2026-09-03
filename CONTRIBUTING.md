# Contributing to Comma Compliance Messages Archiver

Thank you for your interest in contributing! To keep things smooth and secure,
please follow these guidelines.

### 1. Development Workflow

1. **Fork** the repository.
2. **Create** a feature branch (`git checkout -b feature/amazing-feature`).
3. **Commit** your changes using signed commits.
4. **Push** to your branch (`git push origin feature/amazing-feature`).
5. **Open** a pull request against `main` using our template.

This is an Android (Kotlin / Gradle) project. Before opening a pull request,
build and test locally:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug detekt
```

See [README.md](README.md) for toolchain prerequisites (JDK 17 plus the Android
SDK installed by `scripts/setup-toolchain.sh`).

---

## 2. Signed Commits Required

All commits **must be signed** via GPG to comply with our CI's DCO check.

- Step 1: Generate a GPG key.
    ```bash
    gpg --full-generate-key
    ```
- Step 2: List your GPG key ID.
    ```bash
    gpg --list-secret-keys --keyid-format LONG
    # Copy the long key ID e.g 9A1031CEDBC6E80778963E7A57F3B7F86D8B4D9F
    ```
- Step 3: Export your public GPG key.
    ```bash
    gpg --armor --export 9A1031CEDBC6E80778963E7A57F3B7F86D8B4D9F
    ```
- Step 4: Add the GPG key to GitHub.
    [Go to GitHub -> Settings > SSH and GPG keys](https://github.com/settings/keys).
    Click New GPG key, paste the entire key block (from the previous step), then
    click Add GPG key.
- Step 5: Configure Git to use this key.
    ```bash
    git config --global user.signingkey 9A1031CEDBC6E80778963E7A57F3B7F86D8B4D9F
    git config --global commit.gpgsign true
    git config --global user.name "Your Name"
    git config --global user.email "your@email.com"
    ```
- Step 6: Make a signed commit.
    ```bash
    git commit -S -m "feat: add new feature"
    ```
- Step 7: Verify the commit is signed.
    ```bash
    git log --show-signature
    # gpg: Good signature from "Your Name <your@email.com>"
    ```
- Or use the sign-off shorthand to add a Developer Certificate of Origin line:
    ```bash
    git commit -s -m "fix: correct typo"
    ```

We use a GitHub Actions workflow to enforce signed commits. Pull requests with
unsigned commits will show a **"Signature check failed"** status.

---

## 3. Creating a Pull Request

1. Fork the repo and create a branch (e.g., `feature/new-capture-path`).
2. Make changes: add features, tests, documentation updates.
3. Sign off: ensure every commit is signed.
4. Push to your fork.
5. Open a pull request against `main` using our template.
6. Add required reviewers (mention **@JeremiahChurch** and **@sasha** for roadmap
   changes).
7. Wait for CI: it must pass (including the signature check, unit tests, lint,
   and detekt).

---

## 4. Review Process

- We require **at least two reviewer approvals** before merging.
- Maintain a **clean, rebase-able history** with no merge commits.
- For a large number of changes or external proposals, please open an issue
  first for discussion.
- Avoid force-pushing after review; update appropriately, then squash and
  rebase before merge.

---

## 5. Additional Contributions

- **Bug reports**: open an issue and clearly state steps to reproduce and
  expected behavior. **Never paste message contents, recipient addresses, bearer
  tokens, or any other private data** into an issue.
- **Feature requests**: suggest via issue, include the use case and any design
  notes.
- **Docs & templates**: improvements to README, CONTRIBUTING, SECURITY, and the
  issue/PR templates are welcome.

---

Thank you for helping us build a better tool!
