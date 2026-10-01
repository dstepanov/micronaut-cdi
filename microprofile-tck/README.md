# External MicroProfile TCKs on Micronaut CDI

This opt-in test tree runs published Eclipse MicroProfile TCK assertions against external implementations, using this checkout's Micronaut CDI processors and SE container. It covers functional compatibility. It does not test vulnerabilities or claim platform certification.

The build-compatible SmallRye Config adapter now passes all 378 upstream Config tests in 34 classes without skips. Context Propagation has eight passing upstream CDI request/default-injection controls and five independent adapter controls. See [current implementation and remaining work](INTEGRATION-PLAN.md), [Config Lite](config-lite/README.md), [Context Lite](context-lite/README.md) and [runner contracts](support/README.md).

The shared `support` project is a local Arquillian container. It reads each upstream ShrinkWrap deployment, compiles that deployment's sources with the production CDI and Jakarta Interceptors processors, overlays its resources, starts the real CDI SE container, and enriches the unmanaged test instance. Invalid deployments reach the container instead of failing the whole upstream source compilation. Health, OpenAPI and GraphQL use small HTTP transport adapters that delegate behavior to their external implementation. REST Client uses WireMock as the upstream fixtures require.

## Run

Use a JDK (current verification uses OpenJDK 26.0.2.1). Dependencies and official TCK source artifacts are fetched from Maven repositories. This branch requires the accompanying Core provider API in [Core draft PR 13611](https://github.com/micronaut-projects/micronaut-core/pull/13611); supply its matching processor and runtime through `micronautCoreDir` until published. Interceptors can use a local checkout through `jakartaInterceptorsDir`:

```sh
./gradlew microprofileTck -PmicroprofileTck \
  -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors --continue
```

Failures fail the tasks by default. To collect the complete failure matrix in one run, explicitly add `-PmpTckIgnoreFailures`. This diagnostic option does not turn failures into passes; inspect the XML/HTML reports and the evidence summary. Compilation errors, factory failures and missing tests can still fail Gradle with this option.

Run or compare individual components:

```sh
./gradlew :micronaut-microprofile-tck-config:tckSuite \
  :micronaut-microprofile-tck-config:tckImportedSuite \
  :micronaut-microprofile-tck-config:tckReferenceSuite \
  -PmicroprofileTck -PmpTckIgnoreFailures \
  -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors --continue
```

The modes are:

- `tckSuite`: Micronaut CDI with the published vendor integration where available. Explicit library imports/transport still supplied by the driver are documented below.
- `tckImportedSuite`: Micronaut CDI with the vendor portable extension omitted, while archive-owned extensions remain. Config imports its upstream producer; GraphQL attempts a build-compatible discovery adapter. This isolates subsequent behavior and **does not** constitute an equivalent native integration.
- `tckReferenceSuite`: Weld SE 6.0.4.Final, with the same implementation, resources and transport, and vendor portable extensions enabled. Weld implements CDI Lite and Full; Full extension compatibility is not a mandatory Lite requirement.
- `tckLiteSuite`: available for Config and Context Propagation, with their explicit build-compatible adapters and vendor integrations replaced. These tasks are separate from the aggregate native suites.

Every component has all three tasks; only recorded runs in the findings were executed. Ordinary project `test`/`check` do not start these suites. The aggregate runs native suites only. Each upstream test class gets a fresh worker so generated definitions cannot leak between classes. REST Client uses fixed loopback port 8765 because upstream deployment properties contain that literal URL; its HTTPS fixtures also own fixed ports. Do not run its suites concurrently.

## Coverage and versions

| Component | Official TCK | External implementation |
| --- | --- | --- |
| Config | 3.1.2 | SmallRye Config 3.18.3 |
| Fault Tolerance | 4.1.2 | SmallRye Fault Tolerance 6.11.4 |
| Health | 4.0.2 | SmallRye Health 4.3.0 |
| OpenAPI | 4.1.1 | SmallRye OpenAPI 4.3.5 |
| REST Client | 4.0 | RESTEasy MicroProfile REST Client 3.0.1.Final |
| JWT | 2.1 | SmallRye JWT / JWT Build 4.6.4 |
| Telemetry tracing, metrics, logs | 2.1, three TCK artifacts | Helidon MP Telemetry 4.5.5; version fit remains unverified |
| Context Propagation | 1.3 | SmallRye Context Propagation 2.4.0, including CDI/JTA providers |
| Reactive Streams Operators | 3.0.1 | SmallRye Reactive Streams Operators 1.0.13 |
| Reactive Messaging | 3.0.1 | SmallRye Reactive Messaging Provider 4.37.0 |
| GraphQL | 2.0 | SmallRye GraphQL CDI 2.18.5 |
| Metrics API, REST, optional | 5.1.2, three TCK artifacts | SmallRye Metrics 5.1.0 |

The seven MicroProfile 7.1 platform specifications are included, along with five standalone specifications. This is 16 component projects, shared support and two optional Lite adapter modules. [MicroProfile 7.1 requires CDI Lite 4.0; Full and SE are optional](https://projects.eclipse.org/projects/technology.microprofile/releases/microprofile-7.1). LRA is deferred: a meaningful external Narayana LRA run also needs a coordinator and participant HTTP integration. Entire application-server distributions are not booted or transplanted here.

Coordinates are recorded in `components.json` and component build files. Resolved transitive versions and SHA-256 hashes are saved per run. REST Client aligns JAX-RS 3.1 and RESTEasy provider versions. REST Assured 6.0.1 is used for Groovy 5 compatibility with this checkout; [REST Assured 6 requires Groovy 5](https://github.com/rest-assured/rest-assured/wiki/ReleaseNotes60).

## Assertion provenance and limits

Published source JARs are extracted under each component's ignored `build/upstream`; JWT publishes `test-sources` plus a binary `tests` classifier. Upstream Java tests are compiled unchanged with `-proc:none`. The adapters do not replace upstream assertions. Discovery inspects compiled concrete classes without initializing them, including inherited test methods, TestNG class annotations and factories; selected classes are recorded in `build/selected-tests.txt`. Reactive Streams subclasses the upstream factory and supplies a CDI-produced external engine. Reactive Messaging and Metrics use JUnit 4's discovery over the upstream package. The class inventory is not a proof that every optional certification group executed.

The support runner handles local archives, application/request/dependent scopes, field/initializer/test-parameter injection and URI/URL resources. It does not emulate an EE application server, EJBs, JTA, conversations, server-wide Telemetry instrumentation, or JWT-authenticated JAX-RS endpoints. JWT/Telemetry/REST Metrics therefore cannot yet execute their full server contracts. A missing integration must be completed before attributing its blocked tests to CDI. Reflection describes unmanaged test members; full-argument production registrations preserve producer metadata and dependent ownership. Changed definitions in the same parent loader remain guarded and require fresh forks. Managed application beans are compiled through the production processors.

Health imports `SmallRyeHealthReporter`, `AsyncHealthCheckFactory` and the external Config producer. OpenAPI uses the external scanner with deployment Jandex metadata and Config. Reactive Streams imports only its CDI engine producer. Native FT, Context Propagation and JWT also import Config for their prerequisite configuration. Remaining vendor integrations are attempted explicitly rather than accidentally discovered from unrelated TCK JARs. CDI provider selection is explicit in each Arquillian run.

## Results and evidence

See [FINDINGS.md](FINDINGS.md) for measured results and a prioritized fix list. Reports are under `<component>/build/reports/tests/<task>`, XML under `build/test-results/<task>`, and archive/compilation/HTTP failures under `build/deployments/<mode>`. Runtime artifacts are recorded in `build/artifacts-<mode>.tsv`.

Preserve a completed campaign outside ignored build output:

```sh
python3 microprofile-tck/snapshot.py --label NEW_RUN_LABEL
```

The snapshot refuses to overwrite prior evidence. It keeps case statuses/traces, setup failures separately, archive membership, compiler diagnostics, source artifact hashes and revision identities. No upstream source or compiled class copies are committed.
