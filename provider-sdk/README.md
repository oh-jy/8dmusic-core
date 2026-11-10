# Provider SDK

This standalone Java library defines the ABI shared by the host and Android DEX providers.
Platform implementations and their build outputs belong in a separate project; this SDK has no
platform-specific provider, credentials, or Android application dependency.

This ABI is under development. A signed JAR alone cannot be imported until the host's isolated
runner, broker, import confirmation, and lifecycle integration are complete.
