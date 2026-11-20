# FTPS and certificate stores

- Keep client and server certificate stores distinct and preserve existing JKS entries, aliases and configured paths.
- Save JKS files through the active profile write lock and atomic replacement.
- Treat a warning's Continue or Decline choice as a connection decision. Test persisted trust separately through Manage Certificates.
- Display certificate prompts on the Swing event dispatch thread and check that the originating tab is still live before prompting.
- Qualify changes against a real loopback FTPS server with synthetic certificates and the default JDK security settings. Changes to trust policy or protocol compatibility require an explicit decision.
