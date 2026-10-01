# MicroProfile compatibility with Micronaut CDI

Tested on 1 October 2026. Existing implementations are **partly compatible**, but their published CDI integrations are not generally interchangeable with Micronaut CDI. The campaign ran 32 paired fixtures against standard Weld 6.0.4.Final and the public Micronaut SE bootstrap: **21 matched and 11 differed**. Those 11 rows include repeated symptoms of the same problem; they are not 11 independent bugs.

MicroProfile 7.1 requires CDI Lite 4.0; CDI Full and CDI SE are optional. Its component specifications are Config 3.1, Fault Tolerance 4.1, Health 4.0, JWT 2.1, OpenAPI 4.1, REST Client 4.0 and Telemetry 2.1. That requirement concerns the application-facing platform. An implementation may still use CDI Full internally. [Eclipse MicroProfile 7.1 release requirements](https://projects.eclipse.org/projects/technology.microprofile/releases/microprofile-7.1).

The tests cover reusable libraries and their CDI integration, rather than replacing the container inside an entire Quarkus, Helidon or Open Liberty server. JWT checks use valid generated tokens and ordinary claim injection. No vulnerability testing was performed.

## Results by implementation

| Component and implementation | Tested paths | Micronaut result |
| --- | --- | --- |
| Local Micronaut MicroProfile Config, commit `eb97f5fee66c8fa3f941438a74c457915f276210` | Existing Config 3.1 TCK and both integration suites | Configured **376 TCK tests and 10 integration tests pass**. Including the two excluded Optional tests causes setup failure; neither test method executes. |
| SmallRye Config **3.18.3**, resolved Config API **3.1.1** | Programmatic lookup, native extension, imported producers, primitive and container-valued injection | Programmatic access works. Native extension fails at bootstrap. Imported producers work for basic values, defaults, `Config`, `Instance<Integer>`, `OptionalInt` and an injected method. `Optional<Integer>`, `Provider<Integer>`, `List<Integer>` and `Set<Integer>` fail. |
| SmallRye Fault Tolerance **6.11.4**, API **4.1.2** | Native extension and declarative `@Retry`, with a working Config producer and no-metrics integration | Weld retries twice and returns on attempt 3. Micronaut rejects the extension before execution. |
| SmallRye Health **4.3.0**, API **4.0.1** | Imported reporter and async factory; liveness/readiness selection, combined status, payload count, asynchronous check | **All four probes match Weld** after build-time imports. |
| SmallRye OpenAPI **4.3.5**, API **4.1.1** | Model factory and actual Jandex/JAX-RS annotation scanning | **Both probes match Weld**. Endpoint publication was not tested. |
| RESTEasy MicroProfile REST Client **3.0.1.Final**, API **4.0** | Real loopback HTTP call through programmatic builder, native CDI registration, explicit CDI producer/disposer | Builder and explicit producer work. Native extension fails at bootstrap. |
| Helidon MP Telemetry **4.5.5** | Native extension and `@WithSpan` with an enabled SDK and disabled exporters | Weld observes an active span. Micronaut rejects the extension. Helidon 4 documentation describes Telemetry **1.1**, so this does **not** establish Telemetry 2.1 compatibility. [Helidon Telemetry compatibility](https://helidon.io/docs/v4/mp/telemetry). |
| SmallRye JWT / JWT Build **4.6.4**, API **2.1** | Valid RSA token parsing, request-scoped token producer and imported claim producers | Parsing works. With explicit token-type admission, String, Optional and ClaimValue injection work. Group-set injection returns an extra value. Without that admission, request-scoped token injection fails. |

## Integration boundaries, separate from CDI Lite defects

The published JARs for SmallRye Config, SmallRye Fault Tolerance, RESTEasy REST Client and Helidon Telemetry register **portable extensions** under `jakarta.enterprise.inject.spi.Extension`. These are CDI Full integration hooks. Their tested JARs do not advertise a `BuildCompatibleExtension` service.

The first reproducible blocker in each fixture is:

| Fixture | First failing step in Micronaut | Further adaptation needed |
| --- | --- | --- |
| `config-cdi-native`, `config-cdi-native-array` | Extension declares a `ProcessInjectionPoint` observer, rejected during extension inspection | Discovery of Config producers, injection-point processing, custom synthetic beans and validation must move to a supported integration. |
| `fault-tolerance-cdi-native` | `BeanManager.createAnnotatedType()` throws `UnsupportedOperationException` | Build-time binding registration, importing interceptor/support beans, operation discovery and validation are needed. |
| `rest-client-cdi-native` | Extension declares a `ProcessSessionBean` observer, rejected during inspection | Removing that observer alone would not solve dynamic REST-client bean registration. The explicit producer probe demonstrates a narrower working route. |
| `telemetry-cdi-native` | Extension observes the application-scope initialization event as `Object`, rejected during inspection | The extension also registers types and changes method annotations to add interceptor bindings. |

These failures demonstrate that the existing integrations cannot be used unchanged. They do **not**, on their own, prove a violation of CDI Lite, which does not require portable extensions. The rejecting code is in [ReflectivePortableExtensions.java](/Users/denisstepanov/dev/micronaut-cdi/cdi-reflection/src/main/java/io/micronaut/cdi/reflection/ReflectivePortableExtensions.java:325) and [CdiBeanContainer.java](/Users/denisstepanov/dev/micronaut-cdi/cdi/src/main/java/io/micronaut/cdi/runtime/CdiBeanContainer.java:739).

## Functional differences after adapting registration

### 1. SmallRye Config Optional injection selects an Integer producer

Fixture: `config-cdi-import-optional`. The same upstream `ConfigProducer` is explicitly registered with Weld and imported at compilation for Micronaut. The portable extension is disabled in both containers; configuration is registered before bootstrap.

For `@Inject @ConfigProperty(name="mp.compat.number") Optional<Integer> number`, Weld returns `42`. Micronaut invokes `ConfigProducer.getIntegerValue()`; its injection-point metadata makes SmallRye return an `Optional`, which the Integer producer then casts to `Integer`. Creation fails with `ClassCastException`. This is an execution problem after successful import, independent of the portable-extension blocker.

### 2. Deferred Provider lookup loses InjectionPoint

Fixture: `config-cdi-import-provider`. `Provider<Integer>.get()` works on Weld. On Micronaut, the upstream Integer producer receives a null `InjectionPoint`, and SmallRye fails when reading its qualifiers. The equivalent `Instance<Integer>` fixture passes, providing a useful control. The metadata fallback is [CdiCurrentInjectionPointFactory.java](/Users/denisstepanov/dev/micronaut-cdi/cdi/src/main/java/io/micronaut/cdi/runtime/CdiCurrentInjectionPointFactory.java:57).

### 3. Collection injection changes Config values and JWT groups

Fixtures: `config-cdi-import-list`, `config-cdi-import-set`, `jwt-claim-import-groups`.

The Config collection fixtures work on Weld. Micronaut instead resolves element beans, reaches SmallRye's Integer producer with collection injection metadata, and fails with a List/Set-to-Integer cast.

The JWT fixture exposes a quieter failure: Weld injects `[users]`; Micronaut injects `[[users], users]`. The extra element is the string representation of the groups claim, supplied by the String claim producer alongside the elements of the Set producer. This is an actual changed application value.

Collection aggregation is already documented as a deviation in the repository's [CONFORMANCE.md](/Users/denisstepanov/dev/micronaut-cdi/CONFORMANCE.md:162). These probes establish its impact on existing MicroProfile implementations.

### 4. Restricted SE bootstrap drops a normal-scoped produced interface

Fixtures: `jwt-request-producer` and `jwt-request-producer-with-type`.

A selected producer declares `@Produces @RequestScoped JsonWebToken`. A selected request-scoped consumer injects that interface. Both containers receive the producer and consumer classes, and both activate the request context through an injected `RequestContextController`.

Weld returns `alice`. Micronaut fails with `NoSuchBeanException` for `JsonWebToken`. Adding `JsonWebToken.class` to the explicit bean-class list makes the otherwise identical Micronaut fixture pass. The imported JWT claim fixtures include this workaround to isolate claim behavior from the bootstrap failure.

The restriction follows a proxy's target type rather than retaining its selected producer's membership: [MicronautSeContainerInitializer.java](/Users/denisstepanov/dev/micronaut-cdi/cdi/src/main/java/io/micronaut/cdi/se/MicronautSeContainerInitializer.java:277). This is a public SE bootstrap difference; MicroProfile itself does not require SE bootstrap.

## Local Config TCK: configured pass, full run incomplete

The untouched local Config checkout passed its configured 376-test TCK and its two five-test integration suites against this CDI checkout. Its suite omits `CdiOptionalInjectionTest`, containing two specification tests.

The full-suite probe adds that class to the suite without changing any TCK source. Creating `OptionalValuesBean` fails while injecting an Optional method parameter: a String is cast to Optional. The Arquillian setup callback fails and both specification methods are skipped. Gradle reports **379 entries: 376 passed, 1 failed setup callback, 2 skipped test methods**. The extra setup entry is not a third specification test.

This reproduces the limitation recorded in the local Config implementation's conformance notes. It prevents a full Config TCK pass in this environment. Evidence is saved in [the full-suite result summary](/Users/denisstepanov/dev/micronaut-cdi/microprofile-compatibility/evidence/config/full-suite-results.json) and [the failing TCK XML](/Users/denisstepanov/dev/micronaut-cdi/microprofile-compatibility/evidence/config/TEST-org.eclipse.microprofile.config.tck.CdiOptionalInjectionTest.xml).

## Evidence and practical scope

The saved [result matrix](/Users/denisstepanov/dev/micronaut-cdi/microprofile-compatibility/evidence/libraries/results.tsv) contains every paired outcome. Each fixture has its exact source and compiler/deployment/runtime diagnostics under `evidence/libraries/sources`. [The inventory](/Users/denisstepanov/dev/micronaut-cdi/microprofile-compatibility/evidence/libraries/inventory.json) records revisions, counts, timestamps and file hashes; `artifacts.tsv` records resolved dependency hashes, and `origins.tsv` confirms the actual standard Weld classes used.

The reference fixtures all reach their expected results. Positive controls also require Micronaut Config programmatic access, Health aggregation, OpenAPI scanning and REST Client builder/producer calls to work. The harness passing means the experiment is valid, not that all libraries are compatible.

No platform certification is claimed. Health/OpenAPI endpoint hosting, JWT HTTP authentication/RBAC, REST-client provider/interceptor combinations, the remaining Fault Tolerance strategies, Telemetry 2.1, standalone MicroProfile specifications and native-image behavior remain outside these probes. Production implementation sources were not changed.
