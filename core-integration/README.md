# Core injection provider integration

[Core draft PR 13611](https://github.com/micronaut-projects/micronaut-core/pull/13611) adds experimental `@ResolveWith` and `BeanInjectionProvider`. Core resolves the provider as a bean from the active resolution context and passes the full injection argument, qualifier and nullability. Values obtained through that context retain their scope, requesting path and dependent ownership.

The CDI visitor selects `CdiBeanInjectionProvider` for declared array, Optional, Collection, Iterable, Map and Stream injection in actual user members. It uses CDI type and qualifier matching, then asks Core to create the selected definition. Explicit parameter/property/value injection keeps precedence. Missing providers, required null results and incompatible values fail explicitly. Unannotated Micronaut injection retains its existing behavior.

Both the patched processor and runtime are required until the API is published. The provider patch is based on Core `9af0b30128966a93a24172c30342eb468cedb773`:

```sh
git clone https://github.com/micronaut-projects/micronaut-core.git build/optional-collection-core
git -C build/optional-collection-core checkout 9af0b30128966a93a24172c30342eb468cedb773
git -C build/optional-collection-core apply ../../core-integration/optional-collection-injection.patch

./gradlew -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors \
  :test-suite-java:test :micronaut-cdi:check :micronaut-cdi-processor:check
```

Core branch `codex/task-01-bean-injection-provider` owns the provider API. Five Core regressions cover injection routes, container shapes and qualifiers, meta-annotations, ordinary Micronaut controls, property precedence, invalid/missing providers, nullability and dependent ownership. Runtime and processor Checkstyle pass with existing warnings.

Recorded research and campaign evidence are preserved on `codex/archive/microprofile-integration-2026-10-02`, outside the task stack.

## Config environment qualifier fix

The stacked Core branch `codex/config-environment-qualifiers` delegates stereotype-selected annotation values through environment metadata. Without it, qualifiers disappear when annotation members contain `${...}`. The reproduction patch on the Config task branch includes this additional Core fix and its independent regression.

The Config adapter retains SmallRye Config 3.18.3 and passes all 378 unchanged Config 3.1.2 TCK methods without skips. See [Config Lite](../microprofile-tck/config-lite/README.md).
