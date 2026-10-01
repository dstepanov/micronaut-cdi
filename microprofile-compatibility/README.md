# Reproduce the MicroProfile compatibility probes

This opt-in module compares actual MicroProfile libraries on standard Weld 6.0.4.Final and Micronaut CDI. Its 32 fixtures include programmatic access, imported CDI producers, native portable extensions, request scopes, HTTP calls and annotation scanning. The results and their limits are in [FINDINGS.md](/Users/denisstepanov/dev/micronaut-cdi/microprofile-compatibility/FINDINGS.md).

## Library probes

Run from the CDI repository with a Java 25 JDK, using the pinned interceptor checkout already prepared for the differential campaign:

```sh
./gradlew :micronaut-microprofile-compatibility:test \
  -PmicroprofileCompatibility \
  -PjakartaInterceptorsDir=build/fuzz-interceptors
python3 microprofile-compatibility/snapshot.py --label NEW_RUN_LABEL
```

If that checkout is absent, prepare it first:

```sh
git clone https://github.com/dstepanov/micronaut-jakarta-interceptors.git build/fuzz-interceptors
git -C build/fuzz-interceptors checkout d9307e5
```

The test writes its current results under `microprofile-compatibility/build/campaign`. The snapshot preserves sources, diagnostics and hashed dependency metadata under `microprofile-compatibility/evidence/libraries`. It refuses to save a run with failed reference fixtures or harness assertions.

Each fixture is compiled independently: plain Java for Weld, Micronaut/CDI/Jakarta Interceptors processors for the target. Each receives a new class loader and an explicit synthetic archive. Portable-extension service discovery is suppressed to avoid unrelated libraries affecting a case; the selected upstream extension is added explicitly. Imported cases use `@ClassImport` on Micronaut and the same library classes in Weld's bean list.

SmallRye Config's provider is selected explicitly because several dependencies ship competing Config implementations. Its configuration is registered before imported producers are used. Fault Tolerance uses its public no-metrics constructor to isolate retry behavior from optional metrics integrations. Telemetry tracing is enabled with all exporters disabled. REST calls use a temporary loopback server. JWT keys are generated in memory, and only ordinary valid-token behavior is tested.

Helidon's alternate Weld SE artifact is excluded; the harness asserts that the actual reference class originates in `weld-se-core-6.0.4.Final.jar`. The resolved API and implementation versions are recorded rather than inferred from requested coordinates. Config 3.x and Fault Tolerance 6.x were selected for the CDI 4-era MicroProfile baseline; the newer major release lines were not tested.

## Local Config implementation and TCK

The local MicroProfile Config implementation has no Git remote configured. These tests use a clean clone of its local committed sources, revision `eb97f5fee66c8fa3f941438a74c457915f276210`. The original sibling checkout is unchanged.

From the CDI repository, prepare the copy if needed:

```sh
git clone /Users/denisstepanov/dev/micronaut-microprofile-config build/microprofile-config-under-test
git -C build/microprofile-config-under-test checkout eb97f5fee66c8fa3f941438a74c457915f276210
```

Run from `/Users/denisstepanov/dev/micronaut-cdi/build/microprofile-config-under-test`:

```sh
./gradlew :test-suite-java:test :test-suite-no-reflection:test \
  :micronaut-microprofile-config-tck:tckSuite \
  -PmicronautCdiDir=/Users/denisstepanov/dev/micronaut-cdi \
  -PjakartaInterceptorsDir=/Users/denisstepanov/dev/micronaut-cdi/build/fuzz-interceptors
```

That configured suite passes 376 TCK tests and 10 integration tests. To include the omitted Optional injection class without changing the checkout's suite, run from the same directory:

```sh
./gradlew :micronaut-microprofile-config-tck:tckSuite --rerun \
  -I /Users/denisstepanov/dev/micronaut-cdi/microprofile-compatibility/config-tck.init.gradle \
  -PmpConfigSuite=/Users/denisstepanov/dev/micronaut-cdi/microprofile-compatibility/config-full-suite.xml \
  -PmicronautCdiDir=/Users/denisstepanov/dev/micronaut-cdi \
  -PjakartaInterceptorsDir=/Users/denisstepanov/dev/micronaut-cdi/build/fuzz-interceptors
```

The full-suite command is expected to fail in the recorded environment. Its setup callback fails with an Optional injection cast error and the two added specification methods are skipped. The saved configured-suite JSON lists all passing test names; the failing full-suite XML preserves the additional failure.

This is a library compatibility assessment, with one component's TCK. It does not certify a complete MicroProfile platform or compatibility with every implementation.
