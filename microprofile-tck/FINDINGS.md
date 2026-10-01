# External MicroProfile TCK results and proposed improvements

Subsequent local work fixes array assignability, deferred Provider metadata, and Optional/collection injection using an unpublished Core hook. The current campaign has 46 library probes with 41 matches, 6 passing filtered Config TCK methods, and 8 differences among 2,293 CDI comparisons. See [implemented fixes and new evidence](../microprofile-compatibility/FIXES-AND-INVESTIGATION.md). The measurements below are the preserved before-fix baseline.

Tested after pulling Micronaut CDI **`a3d852a`** and Jakarta Interceptors **`d9307e5`**, on 1 October 2026 with OpenJDK 25.0.2. There are **16 external TCK component projects plus shared Arquillian support**. Tests use published upstream assertions and external SmallRye, RESTEasy or Helidon implementations. This is functional interoperability work, not security testing or a certification claim.

Health and OpenAPI execute their selected suites without failures. Reactive Streams executes its upstream factory with a CDI-produced SmallRye engine without failures, retaining the upstream skips. Most other components first need their vendor CDI integration adapted to Lite. The chosen published integrations commonly depend on CDI Full, even though applications can use the MicroProfile platform with CDI Lite. Rejecting a Full-only operation does not itself violate Lite.

Durable [summary](evidence/a3d852a/summary.tsv), [inventory and hashes](evidence/a3d852a/inventory.json), and per-component case/deployment diagnostics are saved alongside this report. Failures below are split into **test failures** and **setup failures**. Skips are not passes. Setup counts are callback/class failures, not counts of independent defects or executed assertions.

## Measured native runs

| External component | Passed | Test failures | Setup failures | Skipped | First blocker / interpretation |
| --- | ---: | ---: | ---: | ---: | --- |
| SmallRye Config | 0 | 0 | 34 | 378 | `ConfigExtension` observes `ProcessInjectionPoint`, unsupported Full hook |
| SmallRye Fault Tolerance | 0 | 0 | 118 | 470 | `BeanManager.createAnnotatedType`, Full operation |
| SmallRye Health | **28** | 0 | 0 | 0 | Reporter and async factory imported; real HTTP status/payload assertions pass |
| SmallRye OpenAPI | **344** | 0 | 0 | 0 | External scanner and model served through local HTTP adapter |
| RESTEasy REST Client | 31 | 0 | 55 | 196 | `RestClientExtension` observes `ProcessSessionBean`; standalone builder tests execute |
| SmallRye JWT | 41 | 34 | 36 | 139 | Standalone token tests pass; no JWT request/token/JAX-RS server integration |
| Helidon Telemetry tracing | 0 | 0 | 24 | 58 | Object-typed extension lifecycle observer rejected |
| Helidon Telemetry metrics | 0 | 0 | 16 | 23 | Same lifecycle observer; server/exporter integration also absent |
| Helidon Telemetry logs | 0 | 0 | 2 | 3 | Same lifecycle observer; server/exporter integration also absent |
| SmallRye Context Propagation | 0 | 0 | 8 | 83 | `SmallryeContextCdiExtension` observes `ProcessProducer` |
| SmallRye Reactive Streams Operators | **1,269** | 0 | 0 | 679 | Upstream factory; skips include optional requirements, untested protocol rules and missing failed publishers |
| SmallRye Reactive Messaging | 0 | 0 | 64 | 0 | JUnit class setup rejected at `ProcessInjectionPoint` observer |
| SmallRye GraphQL | 0 | 0 | 2 | 236 | SmallRye bootstrap cannot resolve unscoped `@GraphQLApi` classes as CDI beans |
| SmallRye Metrics API | 0 | 0 | 40 | 0 | `LegacyMetricsExtension` observes unsupported `BeforeShutdown` lifecycle event |
| SmallRye Metrics REST | 0 | 0 | 4 | 0 | Same extension; registry HTTP integration also required |
| SmallRye Metrics optional | 0 | 0 | 2 | 0 | Same extension; optional scope/server contracts also required |

The **34 JWT test failures are not established CDI defects**: their requests target a server contract the runner has not implemented. There is no authenticated JAX-RS server at the fallback URI. Deployment failures include missing `JsonWebToken`, claim and role producers. Native FT/Config/REST and other extension blockers also prevent attributing their skipped assertions to runtime behavior.

Health/OpenAPI are adapted integrations: their successful runs do not imply that dropping a vendor JAR into any Micronaut server is sufficient. Reactive Streams primarily tests the external engine; its CDI integration is one producer resolved through Micronaut CDI.

## Reference and imported comparisons

| Component / mode | Passed | Test failures | Setup failures | Skipped | Meaning |
| --- | ---: | ---: | ---: | ---: | --- |
| Config / Weld | **378** | 0 | 0 | 0 | Valid reference for the selected upstream Config suite |
| Config / imported Micronaut | 105 | 7 | 11 | 266 | Config producer works for many values; missing vendor synthetic beans/validation and runtime differences remain |
| Health / Weld | **28** | 0 | 0 | 0 | Same transport and external implementation |
| OpenAPI / Weld | **344** | 0 | 0 | 0 | Same scanner/resources/transport |
| REST Client / Weld | **214** | 0 | 0 | 13 | Valid reference; skips are upstream Reactive Streams protocol/optional checks |
| REST Client / imported Micronaut | 132 | 18 | 17 | 77 | Builder paths execute; omitting the extension leaves `@RestClient` interfaces unregistered |
| Context Propagation / imported Micronaut | 11 | 71 | 1 | 1 | Mostly SmallRye CDI provider's missing WeldManager; JTA and optional Full contexts also absent |
| GraphQL / Weld | 220 | 16 | 0 | 0 | Reference has schema/error-output incompatibilities with this implementation/TCK combination |
| GraphQL / imported Micronaut | 0 | 0 | 2 | 236 | Attempted BCE discovery/import still does not resolve API beans; adapter is incomplete |

GraphQL's 16 reference failures include 3 schema/default/character-array assertions and 13 execution/error response comparisons. Both old expected GraphQL Java error messages and SDL shape differ from the selected newer SmallRye stack. They need implementation/TCK version alignment or implementation fixes before these tests can act as a green differential control. They are not Micronaut CDI findings.

The REST Client reference needed a complete RESTEasy provider set, JAX-RS 3.1 alignment, WireMock before upstream setup callbacks, and archive resource names with leading slashes. Those runner fixes are included. The final reference runs with no failures. The imported run's CDI registration failures precede its SSL resource lookups, so resource normalization does not resolve that integration block.

Helidon documentation [describes Telemetry 1.1](https://helidon.io/docs/v4/mp/telemetry), while this runner imports Telemetry 2.1 TCKs. Version fit is unverified. No claim of Telemetry 2.1 implementation conformance is made. The native extension incompatibility is nevertheless reproduced separately with a valid Weld span in the library probes.

## Improvements and fixes to make the suites pass

These are proposed changes, not changes already implemented in the CDI runtime. Prioritize runtime defects confirmed by independent managed-bean probes, then replace vendor Full-only integrations with build-compatible adapters. Removing the first unsupported observer alone will not provide all the registration and validation it performed.

| Priority | Work | Evidence / acceptance target |
| --- | --- | --- |
| 1 | **Preserve InjectionPoint for deferred Provider resolution.** Carry the requesting managed bean's qualified injection-point metadata through `Provider<T>.get()` and nested producer calls. | Config `CDIPlainInjectionTest.canInjectDynamicValuesViaCdiProvider` fails in compiled `DynamicValuesBean`. The paired managed-bean library probe produces the same null InjectionPoint; `Instance<Integer>` is a working control. Make both paths return the configured values. |
| 1 | **Resolve explicit Optional/List/Set producer beans using CDI types and qualifiers before container aggregation.** Avoid selecting Integer/String element producers for an Optional or collection request. If aggregation is retained as Micronaut behavior, offer a CDI-compliant resolution path. | Config probes throw Optional/List/Set-to-Integer casts. JWT groups yields `[[users], users]` instead of `[users]`. This remains a documented CDI deviation with real application effects. |
| 1 | **Implement a SmallRye Config Lite adapter.** Import its producer and register typed configuration/synthetic beans at build time, including arrays, class/custom converters, `@ConfigProperties`, defaults and prefixes. Validate missing/invalid required values at deployment while preserving optional/dynamic semantics. | Imported Config: 6 ConfigProperties assertion failures, 1 managed Provider failure, converter/Optional setup failures, and missing expected deployment rejection in 4 scenarios. Native Config requires adaptation before any of its 378 assertions can run. |
| 2 | **Create a REST Client BCE integration.** Discover `@RegisterRestClient`, register qualified synthetic interface beans, apply configKey/URI/provider configuration, implement request/application/singleton lifecycles and close/disposer behavior. Wire external RESTEasy creation to these beans. | Weld: 214 pass. Imported: 18 failed bean metadata/lifecycle tests and 17 injection setup failures. Session/conversation test cases require explicit optional CDI Full scope support or a declared unsupported certification group; do not label them mandatory Lite requirements. |
| 2 | **Adapt SmallRye Fault Tolerance registration and interception.** Move binding discovery, annotated-operation metadata, interceptor/support bean registration and definition validation to compilation/BCE. Preserve external SmallRye execution behavior. | Native 118 setup failures at Full `createAnnotatedType`. Separate `@Retry` probe works on Weld with 3 attempts. After deployment works, run the full async/timeout/retry/bulkhead/circuit/fallback assertions before claiming compatibility. |
| 2 | **Supply Micronaut context propagation providers.** Keep the external SmallRye engine, but replace its Weld-specific CDI ThreadContextProvider with capture/restore/clear of Micronaut request/application contexts. Add a real JTA integration if transaction propagation is intended. | Imported run: 11 pass, 72 fail, including 58 failures mentioning unsatisfied WeldManager. Standard SmallRye CDI provider cannot be made compatible merely by importing its beans. Session/conversation/JTA depend on separately supplied facilities. |
| 2 | **Port Reactive Messaging deployment discovery.** Register emitters/channels, incoming/outgoing mediators, connectors, config-qualified producers, startup/shutdown and validation through Lite-compatible metadata. | 64 upstream JUnit classes fail during portable-extension inspection. Rerun after registration to expose channel/runtime failures; there are no successful message assertions yet. |
| 3 | **Finish GraphQL API bean discovery.** Ensure the `@GraphQLApi` discovery adapter creates resolvable dependent beans visible to `CDI.current()` and SmallRye `LookupService`, including `createInstance().select().getHandle()`. Diagnose the generated metadata/index registration before changing CDI resolution. | Native and attempted imported/BCE modes both fail on `ScalarTestApi`. Generated bean definitions exist in imported mode, but lookup remains unresolved. This is an unresolved integration issue, not yet an isolated core CDI defect. Fix/reference-align the 16 Weld failures separately. |
| 3 | **Provide functional JWT server integration.** Register request token and claim producers, populate the current token from a real JAX-RS request, apply ordinary role/claim semantics and expose upstream fixture endpoints. | 41 standalone passes; missing producers/server explain current full TCK failures. Valid-token library probes already work except collection aggregation. This work concerns expected functionality, not vulnerability research. |
| 3 | **Adapt Metrics registration and endpoints.** Register metric registries, qualified producer metadata and annotated metric interceptors at build time; provide lifecycle removal and the REST exposition endpoints. | API/REST/optional setup counts 40/4/2; no metric assertions execute. Do not equate legacy Metrics with the separate Telemetry metrics TCK. |
| 3 | **Choose a Telemetry 2.1-compatible external stack and integrate its lifecycle/instrumentation.** Replace Object-typed/Full-only extension wiring, register tracer/meter/logger/interceptors and HTTP instrumentation, and supply the exporters/test collectors the TCK expects. | Three suites block during deployment. Version fit and server instrumentation remain prerequisite work, beyond accepting a portable-extension callback. |
| 3 | **Complete the runner's remaining server/optional contracts.** Support multi-archive/classloader isolation, unmanaged test producer metadata and needed server scopes. Keep explicit required/optional group inventories and compare more adapted suites with Weld. | The current runner is deliberately a local SE/HTTP harness. Missing facilities and stale-definition guards are runner failures, not CDI behavior findings. LRA needs a coordinator and participant HTTP backend before adding its TCK. |

Only Config, Health, OpenAPI, REST Client and GraphQL were run with the Weld reference in this campaign. Context, imported GraphQL and missing-server failures remain exploratory until their integrations have working controls. All other native blockers have archive/diagnostic traces; the earlier paired library probes independently validate native FT/Telemetry behavior on Weld.

## Remaining CDI fuzz results

The refreshed deterministic differential campaign records **11 differing rows out of 2,293**, down from 50 before the pull. The original validation/type-erasure failures are mostly fixed. Remaining rows include two array assignability false positives, collection aggregation, two interceptor array-alias differences, a matching deployment rejection with different text, and five source/reference differences needing careful standards interpretation. See [latest CDI findings](../compatibility-fuzz/FINDINGS.md), rather than treating every differing row as a bug.

The refreshed 32 MicroProfile managed-bean/library probes have **22 matches / 10 differences**. Request-scoped JWT produced-interface admission now matches Weld. [Latest library evidence](../microprofile-compatibility/FINDINGS.md) isolates the Config/JWT behavior differences from the TCK test enricher.

Verification of the ordinary checkout remains green: **319 CDI Java tests and 833 configured CDI TCK tests**. The TCK projects are opt-in and do not affect normal test discovery. Strict suite tasks fail on assertion/setup failures; `mpTckIgnoreFailures` is explicitly diagnostic. Runtime artifacts, source artifact hashes, revision IDs and per-suite timestamps are preserved for reproduction; snapshot runner hashes describe the final helper sources, which evolved during harness bring-up.
