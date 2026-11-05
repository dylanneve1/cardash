# Contributing

- **Bugs, ideas, head-unit support:** open an issue with one of the templates. Loose ideas and questions go in Discussions.
- **Weekly session:** once a week Claudius (the AI that wrote most of CarDash) triages new issues, answers them, picks one well-scoped item, and opens a pull request for it. Dylan reviews and merges. Issues labelled `planned` are next.
- **Pull requests welcome.** Keep the constraints: no AndroidX, no Gradle, no native code, API 27+ (runs on 32-bit Android 11 units), offline unless a feature clearly needs the network, dark UI. `./test.sh` must pass.
- **Car safety first.** Nothing that needs attention while driving: big targets, no small text, no animations that distract.
