# Summary

<!-- What does this PR change, and why? -->

## Test plan

<!--
How was this verified? At minimum, the Android suite should pass:
  ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug detekt
List new/updated tests and any manual verification.
-->

## Checklist

- [ ] All commits are **signed** (GPG) - the DCO / signature check passes.
- [ ] `./gradlew :app:testDebugUnitTest :app:lintDebug detekt` passes locally.
- [ ] New behavior is covered by tests.
- [ ] No private data (message contents, addresses, tokens) is included in code,
      tests, fixtures, or this PR.
- [ ] Roadmap-affecting changes mention **@JeremiahChurch** and **@sasha**.
