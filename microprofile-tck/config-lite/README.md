# SmallRye Config with CDI Lite

This experimental integration replaces SmallRye Config's CDI Full `ConfigExtension` with a build compatible extension. It keeps the SmallRye Config 3.18.3 engine, converters, mappings and `ConfigProducer`. The MicroProfile Config 3.1.2 TCK sources and assertions are unchanged.

The complete locally discovered upstream suite passed on 2026-10-01: **378 tests in 34 classes, zero failures, errors or skips**. This is compatibility evidence for this adapter and harness, rather than a MicroProfile platform certification. The compact class inventory and deployment outcomes are in [evidence](evidence/2026-10-01/).

## Run

The integration currently requires the accompanying Micronaut Core `@ResolveWith` / `BeanInjectionProvider` change. With the local Core and Jakarta Interceptors checkouts used for this investigation:

```shell
./gradlew -PmicroprofileTck \
  -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors \
  :micronaut-microprofile-tck-config:tckLiteSuite
```

Use Gradle `--tests` to select a class or method. `tckSuite`, `tckImportedSuite` and `tckReferenceSuite` remain separate controls; they explicitly suppress this build extension. The Lite task selects this extension and excludes the vendor's CDI Full extension. Application integration must likewise avoid running the original `ConfigExtension` alongside this adapter.

The generated `config/build/test-results/tckLiteSuite` XML is the authoritative test result. `config/build/deployments/lite` contains every compiled archive, deployment outcome and failure trace, including expected invalid deployments. Archive directories include a fork identity to preserve distinct deployments with the same archive name. `artifacts-lite.tsv` records resolved dependency versions and hashes.

## What runs at build time

The discovery phase imports `ConfigProducer` and the `ConfigProperties` qualifier. Enhancement records injection fields and constructor, initializer, producer and observer parameters. It records required value checks without reading the build machine's configuration. Arrays and custom converted classes receive synthetic dependent beans with exact declared types. Existing SmallRye producer methods handle scalar and supported generic requests.

For `ConfigProperties`, enhancement vetoes the ordinary class bean, records requested and default prefixes, and makes the prefix a binding qualifier member. Synthesis creates a bean for each requested class and prefix. Those injection points use the annotation selected CDI provider so resolution honors the enhanced qualifier metadata.

## What runs at startup and injection

The synthetic startup observer registers configuration classes with SmallRye, then validates required values and conversions in the deployment's class loader. Missing required values and invalid conversions produce a deployment failure, including observer parameters and `Instance<T>` requests. Optional values and deferred `Provider<T>` / `Supplier<T>` requests are not eagerly required.

Custom values delegate conversion to `ConfigProducerUtil` with the actual CDI `InjectionPoint`. Configuration classes delegate construction to `SmallRyeConfig.getConfigMapping`. The adapter has no global configuration cache. The harness releases its registered Config and deployment class loader during teardown; a packaged application integration must provide the same ownership and shutdown cleanup.

## CDI support corrected while implementing the adapter

Synthetic metadata now retains array dimensions and parameterized types. Runtime storage uses a compatible declared type while CDI `Bean.getBeanClass()` preserves the implementation class specified by the extension. The synthetic creator receives the requesting field or parameter's injection metadata. Synthetic archive admission follows its creator's origin rather than its returned array class.

Qualifier binding records now serialize the final enhanced annotation metadata. Imported qualifier annotation methods are enhanced before their consumers are compiled, and synthetic qualifier records use that same declaration. The focused Java regression covers `String[][]`, `List<String>` versus `List<Integer>`, actual injection members, and changing an imported qualifier member from nonbinding to binding.

## Remaining work

- This is an opt-in TCK integration module. Publishing it as an application library needs dependency packaging, extension exclusion and application shutdown integration.
- The adapter scans enhanced members because the current BCE `BeanInfo.injectionPoints()` metadata is incomplete. The upstream suite exercises fields and invalid observer/`Instance` requests; inherited members, constructor/initializer parameters, producer parameters and programmatic selection need dedicated adapter tests before claiming complete coverage.
- Synthetic beans exposing unrelated interfaces while declaring `Object` as their bean class still need a more general runtime registration facility. Core requires one primary type assignable to every exposed type. This change supports a compatible type closure and does not relax Core's validation.
- The harness discovers all 34 concrete test classes from this upstream artifact. Adding future upstream versions or a full MicroProfile platform requires rerunning discovery and the relevant platform suites.
