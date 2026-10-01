# Core integration for CDI Optional and collection injection

The [Core patch](optional-collection-injection.patch) enables `CdiInjectionPointResolver` to select a bean of
the full declared Optional or Collection type before Micronaut compiles optional wrapping or element aggregation.
The change is implemented and tested locally against Core commit `55ceb9892508f7102909120788cf4d8dd0edd5c7`.
It has not been published or submitted upstream. The published Core snapshot alone does not contain this hook.

## Implementation

Core loads processor-classpath `BeanDefinitionInjectionPointResolver` services and consults them after explicit
parameter and property/value handling, before the built-in container shapes. Its visitor-context language
resolver remains in the existing fallback position. With no service handling the injection point, Core keeps
its built-in behavior. The patch includes a regression for fields, constructor and initializer parameters, an
explicit property injection control, and a test service that only handles the fixture's namespace.

The CDI processor registers its resolver as a service. It handles Optional and Collection types only in beans
carrying CDI scope metadata. Generated interceptor registration parameters keep their existing Core routing;
legal user field names starting with `$` still receive exact injection. Deployment validation checks full
Optional and Collection types for missing and ambiguous beans. It retains the existing separate rules for
nullable injection, Stream, Map and Iterable types that are not Collections.

## Reproduce from this repository

The current patched checkout is `build/optional-collection-core`. To recreate it when that directory is absent:

```sh
git clone https://github.com/micronaut-projects/micronaut-core.git build/optional-collection-core
git -C build/optional-collection-core checkout 55ceb9892508f7102909120788cf4d8dd0edd5c7
git -C build/optional-collection-core apply ../../core-integration/optional-collection-injection.patch
```

Build and test using the checkout through the new included-build option:

```sh
./gradlew -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors \
  :test-suite-java:test :micronaut-cdi-tck:tckSuite :test-suite-no-reflection:test \
  :micronaut-cdi:check :micronaut-cdi-processor:check

./gradlew -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors -PmicroprofileCompatibility \
  :micronaut-microprofile-compatibility:test

./gradlew -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors -PmicroprofileTck \
  :micronaut-microprofile-tck-config:tckImportedSuite \
  --tests '*CDIPlainInjectionTest*' --tests '*CdiOptionalInjectionTest*'
```

`build/fuzz-interceptors` is the sibling Interceptors checkout used for the campaign. An alternative checkout
can be supplied, or that option can be omitted to use the repository's existing source dependency.

Consumers must be recompiled with the patched Core processor. Once the hook is published, the integration can
use that Core version without the included-build option. This patch does not change external SmallRye or JWT
sources and does not implement their CDI Full portable extensions.

## Verified behavior

The CDI regression checks Optional, List, Set and Collection field injection, constructor/initializer injection,
full generic InjectionPoint metadata, qualifier selection, exclusion of an element producer, and disposal of
each produced container by identity when its owner is destroyed. Deployment regressions reject missing and
ambiguous producers. The paired SmallRye probes cover an absent Optional property, custom conversion inside
Optional/List, all three managed injection routes, and JWT groups and Optional claims.

See [verification.json](evidence/verification.json) for test counts and source/artifact provenance, and
[the fix report](../microprofile-compatibility/FIXES-AND-INVESTIGATION.md) for the measured compatibility results.
The attempted Config ArrayConverter TCK deployment is still blocked by missing array bean types in imported
mode; its failure is preserved separately in [the diagnostic evidence](evidence/config-three-class-cases.json).
