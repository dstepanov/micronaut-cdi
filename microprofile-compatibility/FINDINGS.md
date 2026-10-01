# MicroProfile compatibility after pulling latest changes

Subsequent local work fixes array assignability, deferred Provider metadata, and Optional/collection injection using an unpublished Core hook. The current campaign has 46 library probes with 41 matches, 6 passing filtered Config TCK methods, and 8 differences among 2,293 CDI comparisons. See [implemented fixes and new evidence](FIXES-AND-INVESTIGATION.md). The measurements below are the preserved before-fix baseline.

The refreshed 32 paired functional fixtures on Micronaut CDI `a3d852a` and Jakarta Interceptors `d9307e5` produce **22 matches and 10 differences** against Weld 6.0.4.Final. All reference scenarios meet their expected values and both test drivers pass. This is library compatibility testing, not vulnerability testing.

[Evidence and artifact hashes](evidence/a3d852a-libraries/inventory.json) and [all paired results](evidence/a3d852a-libraries/results.tsv) are preserved. [Earlier detailed findings](FINDINGS-eaecbac.md) concern the previous revisions. The earlier request-scoped JWT interface producer failure is **fixed** in the latest checkout: both implicit and explicit produced-type admission now return `alice`.

## Preserved baseline compatibility

| External implementation | Working paths | Remaining differences |
| --- | --- | --- |
| SmallRye Config 3.18.3 | Programmatic lookup; imported basic/default/Config/Instance/OptionalInt producers; method injection | Native `ProcessInjectionPoint` extension rejected. Imported Optional/List/Set injection calls the Integer producer with incompatible values. Deferred `Provider<Integer>` loses InjectionPoint. |
| SmallRye Fault Tolerance 6.11.4 | Reference retries to attempt 3 | Native extension requires `BeanManager.createAnnotatedType`, a CDI Full operation. |
| SmallRye Health 4.3.0 | All four reporter/async probes match | Build-time reporter/factory imports required by this integration. |
| SmallRye OpenAPI 4.3.5 | Model factory and Jandex/JAX-RS scanner match | No native CDI discovery assertion in these two probes. |
| RESTEasy REST Client 3.0.1.Final | Programmatic HTTP and explicit CDI producer/disposer match | Native extension's `ProcessSessionBean` observer rejected. |
| Helidon Telemetry 4.5.5 | Reference activates a span | Native Object-typed lifecycle observer rejected; Telemetry 2.1 version fit unverified. |
| SmallRye JWT 4.6.4 | Token parsing, request token producers, String/Optional/ClaimValue claims match | Group injection returns `[[users], users]` instead of `[users]`, due to collection aggregation. |

Unsupported portable extensions show an integration boundary. CDI Lite does not require the CDI Full SPI. A platform can expose CDI Lite to applications while its implementation internally needs Full. [MicroProfile 7.1 requirements](https://projects.eclipse.org/projects/technology.microprofile/releases/microprofile-7.1).

The actual Config TCK independently reproduces `DynamicValuesBean`'s Provider injection failure, through a compiled managed application bean. List/Set/Optional and JWT groups are also isolated with real external producers in these probes; they are independent of the test-instance enricher used by the new TCK runner.

For the full external implementation + Micronaut CDI + upstream MicroProfile TCK matrix, runner limitations, and a prioritized improvement list, see [MicroProfile TCK findings](../microprofile-tck/FINDINGS.md). The earlier local Micronaut Config run is retained as historical evidence and is not counted as an external implementation TCK run.
