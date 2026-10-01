# MicroProfile integration implementation and remaining work

The integration branch keeps external implementations and unchanged upstream TCK assertions. Config now has a working build-compatible adapter; Context Propagation has a working request-context adapter and focused upstream controls. Other components still require integration code or server contracts. These results are functional compatibility measurements, not a complete MicroProfile certification claim.

## 1 Annotation selected Core providers

The replacement API is `BeanInjectionProvider`, selected by `@ResolveWith` on an injection point or a meta-annotation. Core retrieves the provider as a bean and calls `get(resolutionContext, argument, qualifier, nullable)`. The full argument preserves generics and metadata, and the active context preserves the requesting member and dependent ownership.

The CDI visitor selects `CdiBeanInjectionProvider` for arrays, Optional, Collection, Iterable, Map and Stream in actual CDI user members. That provider selects by CDI types and final qualifier records, then delegates creation of the selected definition to Core. Ordinary Micronaut beans retain their existing container behavior. Missing providers, required null results and incompatible values fail explicitly; property/value injection retains precedence. The implementation and reproduction patch are described in [Core integration](../core-integration/README.md).

This API must be merged and published in Core, or both its patched processor and runtime must be supplied through the included build. A processor-only update is insufficient because generated definitions call the matching runtime helpers.

## 2 Build time SmallRye Config

The new [config-lite module](config-lite/README.md) replaces discovery, validation and synthetic registration performed by SmallRye's CDI Full extension. It keeps SmallRye Config 3.18.3 and its normal producers as the value/conversion engine. It records injection requests, registers otherwise missing generic/array values and prefixed ConfigProperties beans, validates required values, and preserves InjectionPoint metadata.

The CDI implementation now records array descriptors and full synthetic types, distinguishes synthetic creator infrastructure from the created bean's CDI bean class, admits synthetic instances according to their creator archive, and writes final qualifier binding records after build-compatible enhancement. Dependency annotation types requested by enhancement are processed before consumer metadata is folded.

All 378 unchanged Config TCK methods across 34 classes pass, with no failures, errors or skips. The final property-expression blocker was a Core environment metadata defect, separately covered by a Core regression. Prefix selection uses the runtime provider to honor final CDI binding rules even when native metadata retains an earlier NonBinding mapping.

A general synthetic SPI gap remains: a bean exposing several unrelated declared types cannot always fit Core RuntimeBeanDefinition's single primary implementation type. The array fix supports compatible type closures and does not prove arbitrary unrelated synthetic type sets.

## 3 REST Client

The inspected implementation is RESTEasy MicroProfile REST Client 3.0.1.Final; the harness currently uses the REST Client 4.0 TCK, so version alignment must also be established. `RestClientExtension` discovers `@RegisterRestClient` interfaces and adds `RestClientDelegateBean` at runtime. Its session-bean observer also causes CDI Lite extension validation to reject the original extension. Replacing discovery alone is insufficient: `RestClientDelegateBean.create()` calls `BeanManager.createInterceptionFactory()` for every created client, another CDI Full dependency. These findings come from the pinned implementation sources, not from a failed request attributed to CDI resolution.

Implement a separate adapter that scans all registered interfaces at compilation and registers generated client implementations with `@RestClient` qualification. Keep RESTEasy's builder and HTTP engine. Record the interface, config key and annotation defaults at build time; apply base URI/URL, timeouts, registered providers and connection configuration at runtime through MicroProfile Config.

Generate concrete forwarding implementations, or add equivalent Core support for intercepted interface products, so CDI interceptor bindings are applied to client calls without InterceptionFactory. The forwarding method must preserve the original interface method, arguments, result, thrown exception and InvocationContext semantics. This is especially relevant when Fault Tolerance annotations decorate REST interfaces.

Choose scopes using the configured scope when present, then the interface's declared scope, with Dependent as RESTEasy's default. Close AutoCloseable clients when their owning contextual instance is destroyed, including dependent injection and normal-scoped proxy teardown. A scope selected by runtime configuration requires an explicit registration strategy; it cannot simply be frozen from a different build-time configuration.

Provider classes and ClientHeadersFactory instances must resolve through CDI with their dependencies and lifecycle, while preserving the implementation's fallback for non-CDI JAX-RS providers. Replace any remaining session-bean-only behavior with an explicit unsupported optional group. Async invocation must carry the required client context and release resources after completion or cancellation.

The TCK runner also needs the REST/JAX-RS fixture server, actual deployment URLs, filters and endpoint teardown. Acceptance should start with unchanged injection, provider/header factory, config-key and close/lifecycle tests, then interceptor and asynchronous tests. Report unsupported optional Jakarta EE groups separately. A native address placeholder cannot establish this behavior.

## 4 Build time SmallRye Fault Tolerance

Build-time support is feasible, but has not yet been implemented or verified against the full TCK. Inspection used SmallRye Fault Tolerance 6.11.4, the version pinned by this harness. The execution engine can be retained while the CDI integration layer is replaced. Quarkus has an existing build-time integration; its processor is framework-specific and is evidence of feasibility, not a portable adapter to copy unchanged. [Quarkus implementation](https://github.com/quarkusio/quarkus/blob/main/extensions/smallrye-fault-tolerance/deployment/src/main/java/io/quarkus/smallrye/faulttolerance/deployment/SmallRyeFaultToleranceProcessor.java)

A build-compatible extension or Micronaut processor must record Fault Tolerance annotations on classes and methods, inherited declarations, method signatures and fallback descriptors, and register the interceptor binding, interceptor and support beans. It must reject invalid annotation combinations and fallback signatures at the phase required by the specification. Structural metadata belongs at build time; configuration-derived values still initialize against runtime Config.

SmallRye explicitly offers the `FaultToleranceOperationProvider` SPI. Supply a compiled replacement for `DefaultFaultToleranceOperationProvider`, which currently calls BeanManager.getExtension(FaultToleranceExtension.class). Reconstruct and cache operations from recorded FaultToleranceMethod metadata, preserving the bean class/method key and hierarchy semantics. Replace DefaultExistingCircuitBreakerNames with recorded names; its default implementation also reads that extension.

Replace DefaultFallbackHandlerProvider and DefaultBeforeRetryHandlerProvider. They currently use CDI Full `Unmanaged` to produce, inject, invoke lifecycle callbacks and dispose handler instances. Compile those handler classes as managed dependencies and keep a per-invocation creational context/registration where the external provider requires that lifecycle. Always release it, including handler failure. Merely obtaining a singleton handler from BeanContainer would change semantics.

Keep the SmallRye interceptor and execution engine, but verify timers, executors, async return types, cancellation, request context, circuit breaker naming and metric hooks. Do not substitute a no-op metrics integration when testing required metric behavior. Run unchanged synchronous Retry/Fallback first, then Timeout, CircuitBreaker, Bulkhead and async groups. Source analysis establishes feasibility; passing these groups is still required to establish compatibility.

## 5 Context Propagation

The [context-lite module](context-lite/README.md) registers a standard ThreadContextProvider for Micronaut's CDI request context and uses SmallRye Context Propagation 2.4.0 for execution. Its build-compatible extension registers default ThreadContext and dependent ManagedExecutor producers, with executor shutdown and ContextManager release.

Captured contexts retain the original contextual instances without taking ownership. Cleared contexts create fresh active requests when the origin was active; inactive origins remain inactive. Both restore the receiving thread's prior request and preserve unrelated propagated elements. Expired captured requests are rejected; closure is checked for thread affinity and double disposal.

Five independent adapter controls and eight unchanged upstream CDI request/default-injection tests pass with no skips. Four separate session/conversation tests fail because those CDI Full contexts are absent. JTA and the remaining full matrix are unverified. The module README contains a runnable ManagedExecutor example and reproduction commands.

## 6 Reactive Messaging

Replace portable-extension discovery with recorded incoming/outgoing methods, channels, connector annotations and generic emitter injection points. Register the SmallRye runtime support beans and compile invokers that preserve the method signatures. Construct and validate the channel graph, distinguishing missing channels, incompatible signatures and connector configuration failures.

Provide synthetic Emitter/MutinyEmitter and channel-qualified Publisher/Subscriber beans with full generic types. Preserve acknowledgement, negative acknowledgement, back pressure, blocking execution and failure propagation. Start connectors only after their dependencies/configuration are available; shut down streams, connector resources and executors with the deployment. First acceptance is the unchanged in-memory channel/emitter TCK groups; connector-dependent groups need their actual connector fixtures. This is substantial adapter work, not a general CDI Full API requirement.

## 7 GraphQL

Replace the current fixture-specific imports with discovery of every GraphQL API and its input/output/scalar types. Build an index/schema from the deployment's actual classes and annotations, respecting scopes, qualifiers and schema configuration. Use CDI contextual references for resolvers and fetchers, preserving per-request context, async completion and destruction.

Class identity must be consistent across the schema index, generated bean definitions and test execution loader. A class of the same name from another archive cannot satisfy the lookup. Provide the actual GraphQL HTTP endpoint and JSON transport before interpreting server TCK failures as CDI defects. Version-related failures also reproduced under the Weld reference and must be resolved through API/implementation/TCK alignment. Acceptance is the unchanged schema, scalar, resolver injection and execution groups without hardcoded test-class lists.

## 8 JWT

The existing standalone probes establish several produced-interface and claim-container injection paths; they do not establish the HTTP JWT TCK. Add the external JWT implementation's HTTP/JAX-RS integration so each request exposes the current JsonWebToken, claim producers and role mapping to endpoint code. Keep full generic claim types, qualifiers and request ownership; propagate or clear the current principal consistently on async work and request completion.

Supply the TCK's real endpoints, request filters and configured key/token fixtures. Check declared role behavior and response status assertions as functional compatibility tests. The requested investigation excludes security bug research. Acceptance is the unchanged principal, claim injection, role and request-isolation groups, with unsupported server features reported explicitly.

## 9 Metrics

Treat the legacy MicroProfile Metrics TCK separately from newer Telemetry metrics. Register the required base/vendor/application registries with qualifiers, tags and configured naming. Implement annotation discovery and interception for counted, timed, metered and gauge methods in the pinned Metrics version, including producer metadata and lifecycle-driven registration/removal.

Expose the required metrics endpoints in their expected formats and make registry state independent between deployments. Tests need actual server request metadata and endpoint URLs. First acceptance is producer/registry injection and annotation interception; then add exposition and complete the unchanged endpoint groups. Reusing a registry without the external implementation's CDI registration semantics is insufficient.

## 11 and 12 Shared TCK runner

[Runner hardening](support/README.md) now covers actual unmanaged requesting members, generic types and qualifiers, including deferred Provider access in the presence of a competing producer. It owns dependent field/method registrations, partial enrichment cleanup, HTTP request activation, deployment teardown and archive resources/services. Missing transport adapters fail explicitly.

Five runner contracts, 28 unchanged Health tests, six imported Config tests and six Weld reference Config controls pass. Evidence directories are unique across fresh JVM forks. Generated definitions are tracked per parent loader, with a guard against installing changed bytes over an existing name.

A remaining architectural task is full isolation of several changed deployments inside the same parent test loader. Even identical source can generate different bytes after the shared compiler/runtime annotation cache is populated. Isolate the processor and deployment/test execution loaders, or redesign generated-definition registration with aligned class identity. Removing the guard would make results unreliable. Component-specific REST, JWT, Metrics and Telemetry transports also remain necessary.

## 14 Interceptor and observer investigation

[Draft interceptor PR 7](https://github.com/dstepanov/micronaut-jakarta-interceptors/pull/7) snapshots setter arguments before validating them, so the installed values are exactly those validated. Seven regressions were added; 248 Java tests and 66 unchanged interceptor TCK tests pass, with no skips.

The two Weld parameter-array aliasing differences concern unspecified ownership policy. They are documented differences, not established conformance bugs. The private-observer difference is Micronaut-correct: private Java methods do not override, so an inherited observer retains its superclass body. Three managed regressions cover a same-signature private non-observer, two independent private observers, and a genuine protected override suppressing inheritance. [Interceptors contract](https://jakarta.ee/specifications/interceptors/2.2/apidocs/jakarta.interceptor/jakarta/interceptor/invocationcontext), [CDI inheritance](https://jakarta.ee/specifications/cdi/4.1/jakarta-cdi-spec-4.1.html#inheritance)

## Completion order

Publish the Core provider API and environment metadata fix, then consume that version in the CDI integration. Retain the complete Config TCK as the first adapter acceptance check. Extend Context Propagation only with clearly identified required/optional groups. Next implement the REST Client generated registration/interception adapter and a minimal Fault Tolerance operation/handler adapter. Server transports and loader isolation unlock meaningful results for the remaining server-facing suites.
