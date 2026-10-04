# Repository Agent Instructions

## Code formatting

- Use the root Gradle build's Spotless configuration as the source of truth for
  Kotlin, Java, and Gradle Kotlin script formatting. It covers SDK sources, tests,
  samples, and independent consumer fixtures.
- After changing these files, run formatting and verification from the repository
  root before handing off the work:

  ```powershell
  .\gradlew.bat spotlessApply
  .\gradlew.bat spotlessCheck
  ```

  On Linux/macOS, use `bash gradlew spotlessApply` and `bash gradlew spotlessCheck`.
- Follow `.editorconfig` and `.gitattributes`. Formatter versions and styles are
  pinned in `build.gradle.kts`; do not change them as part of unrelated work.
- Review the resulting diff and keep unrelated user changes intact. For a scoped
  task in a dirty working tree, use Spotless's `-PspotlessFiles` filter to format
  only the intended files, then run the full `spotlessCheck`. Report unrelated
  formatting failures rather than silently rewriting other work.
- Keep formatting changes separate from behavioral changes where practical.
  Do not format generated code, build outputs, or third-party files.
- See [DEVELOPMENT.md](./DEVELOPMENT.md) for contributor commands. Both CI workflows enforce
  `spotlessCheck`.
