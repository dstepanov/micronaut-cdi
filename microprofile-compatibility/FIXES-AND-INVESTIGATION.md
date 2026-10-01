# CDI array Provider Optional and collection fixes

These fixes are included in this branch on top of CDI `a3d852a` and Interceptors `d9307e5`. The saved evidence records the local working tree used for testing. Optional and collection
injection additionally require [the Core source patch](../core-integration/optional-collection-injection.patch),
tested on Core `55ceb9892508f7102909120788cf4d8dd0edd5c7` through `-PmicronautCoreDir=build/optional-collection-core`.
That hook is not in the published Core snapshot. The patch and [reproduction instructions](../core-integration/README.md)
are saved in this repository. Existing consumers must be recompiled with the patched Core processor.

Earlier evidence remains preserved. The [current library evidence](evidence/local-optional-collection-fixes/inventory.json)
and [current differential evidence](../compatibility-fuzz/evidence/local-optional-collection-fixes/inventory.json) identify
the local sources and artifacts used, rather than presenting this as the behavior of the unchanged published versions.

## Fixed: array assignability

`CdiAssignability` now requires identical array element types, including their generic parameters. It keeps the separate covariant event-array rules. [CDI 4.1 typesafe resolution](https://jakarta.ee/specifications/cdi/4.1/jakarta-cdi-spec-4.1.html#typesafe_resolution) requires identical array element types; wildcard matching for a parameterized bean type must not be recursively applied inside an array.

`CdiResolutionCustomizer` applies CDI matching to raw array requests too, so Micronaut's own candidate check cannot admit `Object[]` for a `List<String>[]` producer. `CdiInstance` preserves the selected array's generic component information when creating the bean. A qualified `Object` lookup still resolves an array producer by its recorded type, rather than an erased generated-definition argument.

The two failing corpus lookups now match Weld: neither `Object[]` nor `List<?>[]` resolves the `List<String>[]` producer. Exact generic-array lookup, direct field injection and a qualified Object lookup still work. `GenericArrayBeanTypeTest` covers these routes; the formerly permissive wildcard-array expectation in `AssignabilityRulesTest` is corrected. The deterministic corpus now has **8 differing rows out of 2,293**, down from 11 before the local runtime fixes. The array fixes removed two differences; exact List injection removed one more. The other rows remain separate observations/deviations.

## Fixed: deferred Provider injection metadata

The injected `Provider<T>` had been resolved by Micronaut's ordinary `JakartaProviderBeanDefinition`, whose later lookup did not provide the CDI injection-point metadata the dependent producer needed. The CDI Instance factory already captures that metadata and owns the dependent creations; Instance extends Provider.

The resolution customizer now selects the CDI factory **only when the two candidates are that factory and Micronaut's ordinary Jakarta Provider definition**. It does not resolve conflicts involving user-defined Provider beans. There is no new global metadata cache or thread-local workaround.

`ProviderInjectionPointTest` checks requesting bean/member/qualifiers and selected type for field, constructor and initializer injection, alternates providers after construction, and checks cleanup after a producer throws. The paired `provider-injection-metadata` probe independently matches Weld for field and constructor metadata. SmallRye Config's managed Provider probe now returns `42` on both implementations.

The **unmodified upstream Config TCK `CDIPlainInjectionTest` passes all 4 methods**, including `canInjectDynamicValuesViaCdiProvider`. This was a filtered imported-mode run; it is not a claim that the complete Config TCK passes or that its native portable extension now works.

## Fixed Optional and collection injection

Core previously selected optional wrapping or element aggregation before an exact wrapper/collection bean
lookup. SmallRye producers saw the original InjectionPoint but were called through an element-returning
producer, causing casts to fail. JWT groups mixed the Set producer with String claim values.

| Injection | Previous result | Result with the Core hook and CDI resolver |
| --- | --- | --- |
| Config `Optional<Integer>` | Cast failure through Integer producer | `42` |
| Config `List<Integer>` and `Set<Integer>` | Element enumeration and cast failure | `[1, 2, 3]` |
| JWT `Set<String>` groups | `[[users], users]` | `[users]` |
| Missing Config Optional property | Optional wrapping selected the element producer | Empty Optional, matching Weld |
| Optional/List with custom converted elements | Wrong container routing | Converted values, matching Weld |

`CdiInjectionPointResolver` returns ordinary `BeanInjectionPoint` for Optional and Collection injection in
CDI beans. The Core patch loads resolver services before its built-in container branches, after explicit
parameter/property/value handling. The existing visitor-context language resolver remains in its fallback
position. Core-generated interceptor registration lists retain their original routing. Ordinary Micronaut
beans also keep their existing behavior.

Deployment validation now requires a bean of the full declared Optional or Collection type. Missing or
ambiguous beans are deployment problems. No wrapping or element aggregation is retained as a fallback for
these CDI injection points. Stream, Map and Iterable types that are not Collections remain separate existing
deviations. Typed Instance lookups continue to work as before.

`ContainerTypeInjectionTest` covers fields, constructors and initializers, declared generic InjectionPoint
types and qualifiers, a competing element producer, a legal field name starting with `$`, and disposal of all
11 produced containers by identity when their owners are destroyed. Deployment regressions cover missing
Optional/List/Set/Collection producers and ambiguous Optional/List/Set producers. The Core regression also
checks explicit property injection and seven ordinary Micronaut Optional/collection controls.

The campaign now has **46 paired library probes: 41 matches and 5 differences**. All direct Config and JWT
container probes match Weld, including the nine added constructor, initializer, absent-property and custom
converter probes. The five remaining differences require unsupported CDI Full portable extensions; they are
not failures of the exact-container injection fix. The deterministic CDI corpus's `injection-list` case now
matches Weld and no longer includes the competing element producer.

## Implemented change for the two investigated cases

The proposed strict CDI path is implemented in the Core patch plus CDI processor service and deployment
validation. Both codebases and the related tests pass with the patched Core checkout. This is an unpublished
Core integration requirement: using an unpatched processor snapshot does not enable the early service hook.
See [Core integration](../core-integration/README.md) for the patch, required checkout and build commands.

The unmodified Config TCK's `CDIPlainInjectionTest` and `CdiOptionalInjectionTest` pass **all 6 methods** in
strict filtered imported mode, including present and absent Optional properties. `ArrayConverterTest` was
also attempted: its archive remains blocked before assertions because imported mode lacks exact array bean
types that the native vendor extension would register. This was already a setup failure in the earlier
baseline. The [three-class diagnostic evidence](../core-integration/evidence/config-three-class-cases.json)
preserves the failure and skips; they are not counted as passed tests.

## Verification

- **323** Java CDI tests pass, including the array and new Provider regressions.
- **833** configured CDI TCK tests pass.
- **55** reflection-free tests pass; the runtime `check`, including its no-reflection contract, passes.
- **6** upstream Config TCK methods pass in the two filtered imported-mode classes.
- Differential drivers pass; 2,293 comparison rows are preserved.
- All 46 library probe references meet their expected results; direct container injection and all added controls pass.
- **8** targeted Core tests pass; runtime and processor checks, including the no-reflection contracts, pass.

The full MicroProfile matrix in `microprofile-tck/FINDINGS.md` remains the earlier baseline. Only the Config classes above were rerun here; the native Full-extension blockers and missing server integrations remain outstanding.
