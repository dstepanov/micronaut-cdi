# CDI Lite differential findings after pulling latest changes

Subsequent local work fixes array assignability, deferred Provider metadata, and Optional/collection injection using an unpublished Core hook. The current campaign has 46 library probes with 41 matches, 6 passing filtered Config TCK methods, and 8 differences among 2,293 CDI comparisons. See [implemented fixes and new evidence](../microprofile-compatibility/FIXES-AND-INVESTIGATION.md). The measurements below are the preserved before-fix baseline.

The latest campaign tests Micronaut CDI `a3d852a` and Jakarta Interceptors `d9307e5`, with Weld SE 6.0.4.Final as reference. It made **2,293 comparisons**, recording **11 differing rows**, down from 50 in the earlier checkout. A differing exception message or reference-specific permissiveness is not counted as a new CDI defect.

The actual results, reduced sources and diagnostics are preserved in [latest evidence](evidence/a3d852a-weld-6.0.4.Final/inventory.json), [behavior rows](evidence/a3d852a-weld-6.0.4.Final/results.tsv) and [source rows](evidence/a3d852a-weld-6.0.4.Final/source-results.tsv). [The earlier findings](FINDINGS-eaecbac.md) describe the old revisions and are historical.

## Preserved baseline differences

| Trigger | Weld | Micronaut | Interpretation |
| --- | --- | --- | --- |
| Producer of `List<String>[]`, lookup `Object[]` or `List<?>[]` | Both unsatisfied | Both satisfied | Remaining array assignability difference. The exact `List<String>[]` lookup and bean-type metadata now match. Review array component type rules; do not restore the old erasure diagnosis. |
| Inject `List<String>` with a List producer and an element producer | `[produced]` | `[produced, element]` | Collection aggregation remains an explicitly documented Micronaut deviation, with real Config/JWT effects. |
| Change interceptor `getParameters()` array without `setParameters()`; mutate previously supplied array | Target sees mutation | Target sees original value | Array aliasing differences; distinguish from documented `setParameters` behavior. All 196 explicit parameter-validation cases match. |
| Missing `@Default` qualified dependency | DeploymentException | DeploymentException | Only error text differs; bootstrap validation now works. |
| A void producer | Accepted by this Weld bootstrap | Compile rejection | Reference permissiveness; void producers are invalid. |
| Normal-scoped bean with only injected constructor | Accepted by Weld | Rejected by Micronaut | Weld's relaxed proxy construction permits this; an absent nonprivate no-arg constructor is unproxyable under the standard rules. |
| Static/final Object-returning interceptor method | Accepted by Weld | Rejected by Micronaut | Reference permissiveness for invalid interceptor definitions. |
| Private superclass observer and private same-signature subclass method | `heard=100` | `heard=1` | Needs specification-level investigation. Private methods do not override in Java; do not assume Weld's output is the required result. |

Generic array metadata, raw producer supertype erasure and raw wildcard lookup now match this corpus. Missing/ambiguous injection, observer/disposer dependencies, duplicate/prefix bean names, final injected fields, type-variable injection points, producer/initializer conflicts and normal-scoped null-product exceptions also now match their reduced fixtures. These statements concern these tested cases, not exhaustive conformance.

## Verification

The refreshed Java CDI suite passes **319 tests** and configured CDI TCK passes **833 tests**. The saved Interceptors Java result has **241 passing tests**; the differential campaign uses the updated local interceptor build. Both campaign driver tests pass. Seed is `0xCD140`; the generator is deterministic and `-PfuzzSeed` varies qualifier/handle sequences.

The default Weld 6.x line implements CDI 4.1; [Weld identifies its reference versions](https://weld.cdi-spec.org/documentation/). The old CDI 4.0 Weld 5 run remains archived but was not rerun for the latest checkout. For actual external MicroProfile TCKs and proposed changes, see [MicroProfile TCK findings](../microprofile-tck/FINDINGS.md).
