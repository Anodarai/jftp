# Distribution and runtime resources

- Keep the package root application JAR and its adjacent runtime libraries aligned with the manifest and launch scripts. Verify the extracted Windows package from a different working directory.
- Keep Windows Java selection through `JAVA_HOME` and `PATH` covered by the [package check](../test/scripts/Test-WindowsPackage.ps1), alongside native startup and Help QA.
- Preserve the distinct resource roots for base and translated bundles, icons and JavaHelp. Resource paths are part of the startup contract, and Help is initialized during main-window construction.
- Keep test dependencies out of the packaged runtime. Check the manifest and actual archive whenever packaging changes.
