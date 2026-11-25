`test-signing.jks` signs CarDash's CI test builds (main, next and PR builds), so each new test APK installs over
the previous one without uninstalling. Its passwords are in build.sh, which makes it public: it says "this came from
CarDash CI", not "this is an official release". Releases will be signed with a separate key kept as a CI secret.
