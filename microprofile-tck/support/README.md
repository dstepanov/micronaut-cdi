# MicroProfile TCK runner contracts

The shared runner recompiles each ShrinkWrap deployment with the production CDI and Core processors and executes the upstream tests without modifying their assertions. The runner owns unmanaged test injection, deployment resources and HTTP request context activation. It does not supply an application server automatically.

Unmanaged test fields, initializer methods and test method parameters carry their actual reflective member, generic type, qualifiers and annotated metadata. Micronaut lookups use the full declared argument through a bean registration and the production current-injection-point stack. This retains metadata for producer calls and deferred Provider or Instance access. The runner uses CDI scope metadata to track dependent registrations in a creational context. Field and initializer dependencies belong to the test instance until deployment teardown; test method dependencies are released after invocation, including partially completed enrichment that fails. Weld reference enrichment uses its BeanManager with the same requesting-point metadata.

Deployment cleanup stops every registered endpoint, releases test dependencies, closes the CDI container, releases the registered configuration and closes the archive loader. Each step runs despite an earlier failure, with failures aggregated. The previous configuration resolver and thread context loader are restored. HTTP workers capture the deployment controller and loader, activate distinct request contexts, deactivate them on completion and restore thread state even after errors. An HTTP resource request fails explicitly when no endpoint adapter supplied a URI.

The compiler combines archive-owned build-compatible extension service declarations with the explicitly selected `mp.tck.buildExtensions` list. Explicitly setting that property to an empty string suppresses incidental classpath integrations while retaining archive-owned extensions. Overrides are reset after compilation. The `mp.tck.imports` list imports supporting classes for compilation and adds them to the runtime bean-class filter. Lite mode excludes the manually selected vendor portable extension; portable extensions declared by the archive still run.

Test discovery inspects compiled classes without initializing them. It selects concrete classes with public inherited JUnit or TestNG test methods, TestNG class annotations and factories, and records selected and excluded source declarations in `test-discovery.tsv`. This inventory identifies available test entry points, not certification or an automatic classification of optional specification groups. Discovery rejects an empty inventory, and test execution also fails when no tests are discovered. Explicit class and method filters narrow the selected class files.

Each evidence directory contains a fork process identifier and a timestamp as well as its local ordinal and archive name. A serial deployment index records the outcome and directory. This prevents different fresh JVMs from overwriting a same-named archive's failure and compiler diagnostics.

Generated definitions installed beside their original host classes are tracked per parent classloader. Separate parents can install different definitions with the same name. Reusing a parent with different definition bytes is rejected, because silently retaining another archive's definition could corrupt results. The runner therefore retains one fresh test JVM per test class.

Full isolation of multiple changed deployments within one parent test classloader remains unsupported. Even recompiling an identical archive can change generated definition bytes: Core omits annotation-type registration instructions when the shared runtime annotation cache already contains those types. This was observed and compared in the two compilations exercised by the extension-reset contract. Solving this requires aligning the test's execution loader with deployment class identity, or a different generated-definition registration mechanism; removing the guard is not a safe solution. Resources and services are isolated per archive, but that does not imply complete Java class isolation.

The generic transport supports delegated loopback handlers and request contexts. JWT, REST/JAX-RS fixture deployment, Metrics exposition and Telemetry collectors still need component-specific endpoint implementations. The runner fails those unmet contracts instead of presenting a dummy HTTP address.

Run the focused production-processor contracts with:

```sh
./gradlew -PmicroprofileTck \
  -PmicronautCoreDir=build/optional-collection-core \
  -PjakartaInterceptorsDir=build/fuzz-interceptors \
  :micronaut-microprofile-tck-support:test
```

The tests exercise actual producer metadata, a competing producer during qualified deferred access, dependent disposers, failed enrichment cleanup, HTTP request instances and destruction, archive-owned extension execution and reset, service/resource isolation, per-loader generated definitions and teardown after an endpoint fails. Verification results are recorded in `../evidence/runner-hardening/verification.json`.
