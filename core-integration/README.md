# Core injection provider integration

[Core draft PR 13611](https://github.com/micronaut-projects/micronaut-core/pull/13611) adds experimental `@ResolveWith` and `BeanInjectionProvider`. [The stacked API simplification](https://github.com/dstepanov/micronaut-core/pull/1) removes the separate nullability flag: Core passes the full injection argument and resolved qualifier, and validates any null result against nullable and non-required injection semantics. Values obtained through that context retain their scope, requesting path and dependent ownership.

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

Core branch `codex/task-01-bean-injection-provider` owns the provider API. The stacked `codex/bean-injection-provider-nullability` follow-up retains seven Core regressions covering injection routes, container shapes and qualifiers, meta-annotations, ordinary Micronaut controls, property precedence, invalid/missing providers, nullability and dependent ownership. It adds non-required field/initializer injection and a context-provided `@EachBean` qualifier absent from argument metadata. Runtime and processor Checkstyle pass with existing warnings.

Recorded research and campaign evidence are preserved on `codex/archive/microprofile-integration-2026-10-02`, outside the task stack.
