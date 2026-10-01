# Core providers for CDI injection

[Core draft PR 13611](https://github.com/micronaut-projects/micronaut-core/pull/13611) introduces an annotation-selected runtime provider. CDI uses it to resolve the full declared container type and final CDI qualifiers while Core retains creation, scopes, the requesting injection path and dependent ownership. Consumers must compile with the patched Core processor and run with its matching runtime until that API is published.

## Provider contract

```java
@Singleton
class ExactProvider implements BeanInjectionProvider {
    public <T> T get(BeanResolutionContext resolutionContext,
                     Argument<T> argument,
                     Qualifier<T> qualifier,
                     boolean nullable) {
        return nullable
            ? resolutionContext.findBean(argument, qualifier).orElse(null)
            : resolutionContext.getBean(argument, qualifier);
    }
}

@Inject
@ResolveWith(ExactProvider.class)
List<String> values;
```

Core resolves the provider as a bean from the active resolution context, then passes the full `Argument`, qualifier and nullability flag. The provider sees the requesting member on the current path. Prototype provider instances and values resolved through that context belong to the requesting bean. Arbitrary objects constructed by a provider do not acquire a bean lifecycle automatically; the provider must not retain the active context.

`@ResolveWith` supports fields, parameters and meta-annotations. It selects resolution behavior and does not itself mark an injection point or qualify a bean. Explicit parameter/property/value injection takes precedence. Missing providers fail without falling back to aggregation. Required null results and values incompatible with the requested raw type fail with the requesting path. Unannotated Micronaut injection continues to use its normal optional wrapping and collection flattening.

The CDI processor annotates actual user container injection points in CDI beans, including fields, the selected constructor, initializer parameters and injected producer/observer/disposer parameters. It covers arrays, Optional, Collection, Iterable, Map and Stream. Generated proxy infrastructure retains its own routing. `CdiBeanInjectionProvider` resolves using CDI bean types and final qualifier binding records, then asks the active Core context to create the selected definition. This also handles qualifier changes made by a build-compatible extension that native cached annotation metadata does not reflect.

Deployment validation rejects missing and ambiguous full container producers. Core's built-in BeanProvider helper interfaces cannot accidentally satisfy an ordinary Iterable injection. Legal user field names beginning with `$` still undergo validation.

## Environment qualifier metadata

The Config property-expression TCK exposed an independent Core defect: environment-aware annotation metadata delegated qualifier names but inherited the empty default implementation for qualifier values selected by stereotype. The patch now delegates those values and wraps them consistently with other environment metadata APIs. A regression covers an unresolved nonbinding default retained as literal metadata and a resolved binding value. This prevents qualifiers from disappearing simply because an annotation contains `${...}`.

## Reproduce

The patch is [optional-collection-injection.patch](optional-collection-injection.patch), against Core `9af0b30128966a93a24172c30342eb468cedb773` on `5.3.x`. The historical filename is retained for existing reproduction links. Its implementation uses runtime providers rather than processor ServiceLoader resolvers.

```sh
git clone https://github.com/micronaut-projects/micronaut-core.git build/optional-collection-core
git -C build/optional-collection-core checkout 9af0b30128966a93a24172c30342eb468cedb773
git -C build/optional-collection-core apply ../../core-integration/optional-collection-injection.patch

./gradlew -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors \
  :test-suite-java:test :micronaut-cdi-tck:tckSuite :test-suite-no-reflection:test \
  :micronaut-cdi:check :micronaut-cdi-processor:check

./gradlew -PmicroprofileTck \
  -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors \
  :micronaut-microprofile-tck-config:tckLiteSuite
```

The Interceptors checkout can be replaced by another compatible checkout through the supplied property. The new interceptor fix is in [draft PR 7](https://github.com/dstepanov/micronaut-jakarta-interceptors/pull/7).

## Current verification

Six Core regressions pass, covering all three managed injection routes, container types and qualifiers, meta-annotation selection, ordinary Micronaut controls, explicit property precedence, invalid results, missing providers and dependent ownership. Core runtime and processor Checkstyle checks pass, with existing warnings.

The complete Config Lite run passes all 378 unchanged upstream methods with SmallRye Config 3.18.3. See [the adapter](../microprofile-tck/config-lite/README.md) and [the remaining integration work](../microprofile-tck/INTEGRATION-PLAN.md). Current verification is separate from older campaign files retained in `evidence/`.
