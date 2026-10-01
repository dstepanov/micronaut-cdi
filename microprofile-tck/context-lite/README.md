# SmallRye Context Propagation with Micronaut CDI Lite

This module keeps SmallRye Context Propagation 2.4.0 as the execution engine and replaces its
Weld-specific CDI provider with `MicronautCdiContextProvider`, registered through the standard
`ThreadContextProvider` service. `ContextBuildExtension` registers compiled `ContextAccess` and
default `ContextProducers` beans using the CDI build-compatible extension SPI.

The optional `tckLiteSuite` task excludes the original SmallRye CDI and JTA provider artifacts.
The native, imported and Weld reference tasks retain their existing integration classpaths.
The adapter jar is named `micronaut-cdi-context-lite`; it is an integration artifact, not an
upstream TCK artifact.

## Request semantics

`RequestScope.capture()` retains the same contextual bean instances without acquiring their
ownership. Starting it masks only this container's previous request elements; other propagated
elements and other containers' requests are preserved. Closing restores the receiving thread's
previous request, including through nested invocations or exceptional completion.

`captureCleared()` captures whether a request is active. For an active origin it creates a fresh
active request for each invocation, matching the upstream TCK's expected `UNINITIALIZED` state.
Those fresh instances are destroyed before restoring the receiving request. An inactive origin
remains inactive, and masks any existing receiving request during the invocation.

Handles must close on the thread that began them. Repeated close is harmless and does not destroy
beans twice. Starting a captured request after its owner ended it fails explicitly. Capturing does
not extend request lifetime: applications must finish propagated work before ending its owning
request. The adapter uses Micronaut's thread-local propagation mode, as does the existing
`RequestContextController.activate()` enter/exit API.

Application and singleton contexts remain associated with their container. This module does not
provide session/conversation contexts or a transaction manager. Its CDI provider therefore does
not implement those CDI Full contexts, and no JTA provider is installed by the Lite task.

## Example

Compile the application with this module on both its runtime and annotation-processor paths.
SmallRye still supplies builder execution and runtime MicroProfile Config defaults.

```java
@RequestScoped
public class RequestState {
    private String value = "UNINITIALIZED";
    public String value() { return value; }
    public void value(String value) { this.value = value; }
}

// While the owning CDI request is active:
requestState.value("request-42");
ManagedExecutor executor = ManagedExecutor.builder()
    .propagated(ThreadContext.CDI)
    .cleared(ThreadContext.ALL_REMAINING)
    .build();
try {
    assert executor.supplyAsync(requestState::value).get().equals("request-42");
} finally {
    executor.shutdown();
}
```

Default injected `ManagedExecutor` instances are dependent beans and shut down through their
disposer. Application-produced executors remain the application's responsibility. `ContextAccess`
releases its deployment-classloader ContextManager when its container is destroyed.

## Verification on 2026-10-01

- Five independent adapter controls pass, with no failures or skips. These cover receiving-thread
  restoration, nested capture, preserving unrelated context elements, ownership/disposal, fresh
  cleared requests, failure cleanup, inactive capture, expired capture rejection, wrong-thread
  close and repeated close. They also exercise actual SmallRye builders and the service provider.
- All four unchanged upstream `BasicCDITest` methods pass.
- All four unchanged request-context methods in `CDIContextTest` pass, with no skips:
  ManagedExecutor request propagation/clearing and ThreadContext propagation/clearing.
- The four session/conversation methods in the same upstream class fail because Micronaut CDI has
  no such contexts. These are separate CDI Full integration prerequisites, not request-provider
  successes. The JTA and full remaining Context Propagation matrix have not been established by
  this focused verification.

Reproduce:

```shell
./gradlew -PmicroprofileTck \
  -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors \
  :micronaut-microprofile-tck-context-lite:test

./gradlew -PmicroprofileTck \
  -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors \
  :micronaut-microprofile-tck-context-propagation:tckLiteSuite \
  --tests org.eclipse.microprofile.context.tck.cdi.BasicCDITest \
  --tests '*CDIContextTest.*RequestScopedBean' \
  --tests '*CDIContextTest.testCDITCCtx*'
```

The Core override currently includes the parent task's new provider APIs; do not use an unpatched
published Core dependency until those APIs are available. Evidence is saved in `evidence/verified`.
