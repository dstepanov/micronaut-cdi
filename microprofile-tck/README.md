# MicroProfile TCK task stack

This opt-in runner recompiles upstream ShrinkWrap deployments using the production CDI and Core processors. Upstream assertions remain unchanged. The support project owns unmanaged injection metadata, dependent cleanup, request contexts and deployment resources.

```sh
./gradlew -PmicroprofileTck \
  -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors \
  :micronaut-microprofile-tck-support:check
```

See [runner contracts](support/README.md) and [Core integration](../core-integration/README.md). The runner uses fresh test JVMs because changed generated definitions cannot share one parent loader safely.

The five task branches stack on main: injection provider, runner, Config Lite, Context Propagation, then private observer inheritance. Broader component harnesses are on `codex/microprofile-tck-components`. Historical fuzzing, reports and test evidence are preserved on `codex/archive/microprofile-integration-2026-10-02`.
