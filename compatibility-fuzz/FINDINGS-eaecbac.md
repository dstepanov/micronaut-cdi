# CDI Lite differential fuzz findings

The campaign reproduced **10 new behavior or validation gaps**, two already documented differences, and additional reference/interceptor observations. The primary run made **2,293 comparisons against Weld 6.0.4.Final** and recorded **50 differing rows**. Related rows are grouped below; 50 rows do not mean 50 independent bugs. This work concerns functional CDI Lite compatibility.

## Revisions and reference

| Component | Tested revision |
| --- | --- |
| Micronaut CDI | `eaecbac4ec0c741d0a2c87dda681145c86b85002` |
| Micronaut Jakarta Interceptors | `29c80fe1cb0c5300ac7985e5bc316bde00e9b741` |
| Primary reference | Weld SE `6.0.4.Final`, CDI 4.1 |
| Earlier cross-check | Weld SE `5.1.7.Final`, CDI 4.0; 50 source mutations and the same 2,224 behavioral/metadata comparisons |
| Runtime | OpenJDK `25.0.2`, Gradle `9.7.1`, Micronaut `5.3.0-SNAPSHOT` |
| Recorded date | 1 October 2026 |

The README claims CDI 4.0; the dependency catalog uses CDI 4.1 and Interceptors 2.2. Both reference generations were therefore exercised. Weld identifies its [6.x line as the CDI 4.1 reference implementation](https://weld.cdi-spec.org/documentation/). Saved [artifact hashes](evidence/weld-6.0.4.Final/artifacts.tsv) identify the snapshot binaries actually used; future snapshots may change these results.

## Newly reproduced gaps

The first five findings deserve the earliest attention because they affect valid lookups or deployment validation. The remaining findings are invalid-definition handling and the observable exception for a null product. Attribution and suggested locations are based on local source inspection; implementation fixes were not attempted.

| Finding | Minimal trigger | Weld 6 | Micronaut CDI |
| --- | --- | --- | --- |
| F01 Generic array component types are erased | `@Produces List<String>[]` and `Instance<List<String>[]>` | Returns the produced array | `UnsatisfiedResolutionException`; `Bean.getTypes()` reports `List[]` |
| F02 Raw producer supertypes retain type variables | Raw `Box` implements `View<T>`; lookup `View<String>` | Unsatisfied | Satisfied; metadata incorrectly contains `View<T>` |
| F03 Raw beans match an unbounded wildcard | Raw `Box` producer and lookup `Box<?>` | Unsatisfied | Satisfied |
| F04 Dependencies are not validated at bootstrap | Missing or ambiguous direct injection; missing observer/disposer parameter | Rejects initialization | Starts; direct injection fails later, observer/disposer fixtures are admitted |
| F05 Invalid bean names are not validated at bootstrap | Two beans named `same`, or names `same` and `same.child` | Rejects initialization | Starts |
| F06 An injected final field is silently ignored | `@Inject final BeanManager x = null` | Rejects the definition | Creates the bean with `x == null` |
| F07 An injection point declared as a type variable is accepted | `@Dependent class A<T> { @Inject T x; }` | Rejects the definition | Compiles and starts |
| F08 A producer method can also be an initializer | `@Produces @Inject String value()` | Rejects the definition | Compiles and starts |
| F09 A final method on an injected normal-scoped bean is accepted | Inject an `@ApplicationScoped` class declaring a public final method | Rejects initialization | Creates and uses the bean |
| F10 A null normal-scoped product produces the wrong exception | `@Produces @ApplicationScoped Runnable` returns null; injected reference is used | `IllegalProductException` | `DependencyInjectionException` caused by `NoSuchBeanException` |

### Type resolution reproductions

The generated grammar has 22 deployments and 73 required types, for 1,606 resolution comparisons, plus 22 bean-type metadata comparisons. The runtime source reproductions independently compile the reduced cases in each implementation.

F01 has both a missed match and a false match: Weld matches `List<String>[]` and rejects `List<?>[]`, while Micronaut does the opposite. `RecordedTypeValues.of` only records generic arguments when `dimensions == 0`, which explains the observed erasure. `CdiAssignability.isAssignable` also recursively applies generic assignability to array components. CDI requires identical array element types. [CDI 4.0 sections 2.1.2.1 and 2.4.2.1](https://jakarta.ee/specifications/cdi/4.0/jakarta-cdi-spec-4.0)

For F02, `BeanTypesVisitor.collect` walks raw types' supertypes without preserving erasure. The raw `Box` producer has `{Box, View, Object}` in Weld, but `{Box, View<T>, Object}` in Micronaut. A raw `List` similarly acquires `Collection<E>`, `Iterable<E>`, and `SequencedCollection<E>`. The resulting unbounded variables incorrectly admit concrete lookups such as `Collection<String>`.

F03 also occurs on the producer's raw leaf type, independently of F02. `CdiAssignability.saysNothing` explicitly accepts an unbounded wildcard. CDI permits the raw-to-parameterized match for `Object` or unbounded type variables, without adding wildcards to that list. [CDI 4.0 section 2.4.2.4](https://jakarta.ee/specifications/cdi/4.0/jakarta-cdi-spec-4.0)

The full sources are saved under [generic-array-lookup](evidence/weld-6.0.4.Final/sources/generic-array-lookup/), [raw-supertype-lookup](evidence/weld-6.0.4.Final/sources/raw-supertype-lookup/), and [raw-wildcard-lookup](evidence/weld-6.0.4.Final/sources/raw-wildcard-lookup/).

### Deployment and definition validation

F04 and F05 are failures of the public `MicronautSeContainerInitializer` bootstrap, rather than differences between compiler-time and deployment-time rejection. The source fixtures compile successfully and initialize successfully. CDI requires dependency and name validation during deployment. [CDI 4.1 sections 5.2.2, 5.3.1 and 13.2](https://jakarta.ee/specifications/cdi/4.1/jakarta-cdi-spec-4.1.html)

F06 is an invalid field declaration that Weld rejects but Micronaut silently ignores. For F07, `InjectionPointRulesVisitor` exempts generic bean classes from the type-variable check; the tested class is a concrete managed bean, not an abstract generic superclass. F08 is missing an explicit `@Produces`/`@Inject` conflict check. F09's proxyability record is not enforced by this bootstrap. [CDI 4.1 sections 3.2.2, 3.6, 3.10 and 5.2.3](https://jakarta.ee/specifications/cdi/4.1/jakarta-cdi-spec-4.1.html)

The relevant source fixture IDs in [source-results.tsv](evidence/weld-6.0.4.Final/source-results.tsv) are `missing-injection`, `ambiguous-injection`, `missing-observer-injection`, `missing-disposer-injection`, `duplicate-bean-name`, `prefix-bean-name`, `inject-final-field`, `inject-type-variable`, `producer-inject`, and `normal-final-method`. Each has its full source and diagnostics in `evidence/weld-6.0.4.Final/sources/<fixture ID>/`.

### Null normal-scoped product

F10's [saved Micronaut trace](evidence/weld-6.0.4.Final/sources/normal-null-producer/micronaut/runtime.txt) ends in `NoSuchBeanException`, so this is not merely the expected product exception wrapped one level deeper. The null producer is treated as an absent bean. A non-dependent producer returning null must expose `IllegalProductException`. [CDI 4.1 section 3.2](https://jakarta.ee/specifications/cdi/4.1/jakarta-cdi-spec-4.1.html)

## Interceptor observations

Two array-aliasing experiments differ:

| Experiment inside an interceptor | Weld | Micronaut Jakarta Interceptors |
| --- | --- | --- |
| Change `ctx.getParameters()[0]` without calling `setParameters` | Target receives the changed value | Target receives the original value |
| Pass an array to `setParameters`, then mutate that array | Target receives the later mutation | Target receives the value copied at the setter call |

These are reproducible compatibility observations, **not established specification violations**: the [InvocationContext API](https://jakarta.ee/specifications/interceptors/2.2/apidocs/jakarta.interceptor/jakarta/interceptor/invocationcontext) specifies reading and setting values without explicitly specifying array identity/aliasing. Micronaut deliberately copies the arrays in `AbstractInvocationContext.readParameters` and `writeParameters`.

All 196 argument replacement combinations agree, including invalid argument types, nulls, primitive wrappers, reference arguments, and arrays. The zero-argument setter control also agrees. Binding inheritance/overrides, interceptor visibility, two `proceed()` calls with and without recovery from an exception, and void/Object lifecycle interceptor traces agree in the exercised fixtures. The around-construct and destruction paths ran through the current interceptor repository's processors and runtime.

## Already documented differences reproduced

| Fixture | Weld | Micronaut |
| --- | --- | --- |
| Unqualified injection when the only bean has a custom qualifier | Unsatisfied deployment | Injects that qualified bean |
| Inject `List<String>` when a list producer and a string producer exist | `[produced]` | `[produced, element]` |

Both are already described in the repository's `CONFORMANCE.md`; they are not counted among the ten newly reproduced gaps.

## Reference behavior that needs separate interpretation

The raw results also preserve these differences, without attributing them as Micronaut defects:

- A private superclass observer and an unannotated private subclass method with the same signature invoke different bodies: Weld increments the subclass counter by 100, Micronaut invokes the superclass observer and increments by 1. This appears to be a Weld dispatch anomaly, since private methods are not overridden; it needs reference-side confirmation.
- Weld accepts static/final `@AroundInvoke` methods that Micronaut rejects. Those method modifiers are prohibited by [Jakarta Interceptors section 2.6](https://jakarta.ee/specifications/interceptors/2.2/jakarta-interceptors-spec-2.2), so these are stricter, appropriate Micronaut validation.
- Weld admits a void producer fixture that Micronaut refuses. This invalid/degenerate producer is retained as a reference acceptance difference, without using it to claim a Micronaut regression.

The earlier Weld 5 run also accepted duplicate around-invoke methods that Weld 6 correctly rejects. This is why the primary classification uses the matching Weld 6 generation.

## Why the configured compatibility kit still passes

The freshly executed configured suites passed:

| Suite | Executed | Failures | Skipped |
| --- | ---: | ---: | ---: |
| CDI `tckSuite` | 833 | 0 | 0 |
| CDI Java suite | 300 | 0 | 0 |
| Jakarta Interceptors Java suite | 241 | 0 | 0 |
| Differential campaign driver tests | 2 | 0 | 0 |

These are the repository's configured test selections, not a claim that every upstream TCK test ran.

The TCK adapter calls `DeploymentValidator.validate` in `cdi-tck/src/test/java/io/micronaut/cdi/tck/arquillian/MicronautDeployableContainer.java`. That **test-only** validator walks injection points, checks duplicate/prefix bean names, and rejects `CdiUnproxyable` records. The public SE bootstrap does not run this validator. This explains how the kit rejects invalid deployments while ordinary applications admit F04, F05 and F09. The report's inference is supported by the independent bootstrap reproductions and the adapter source; the kit result alone does not establish public-bootstrap conformance.

## Coverage and reproducibility

| Campaign component | Comparisons |
| --- | ---: |
| Generated type resolution | 1,606 |
| Bean-type metadata | 22 |
| Seeded qualifier selections and controls | 323 |
| Generic event dispatch | 8 |
| Interceptor parameter replacements and array controls | 199 |
| Seeded handle lifecycle sequences | 64 |
| Documented injection deviations | 2 |
| Independently compiled source mutations | 69 |
| Total | 2,293 |

The exact generated fixtures, deterministic seed, [full behavioral results](evidence/weld-6.0.4.Final/results.tsv), [source results](evidence/weld-6.0.4.Final/source-results.tsv), Java sources, diagnostics, and dependency hashes are retained. The evidence inventory includes baseline revisions and test counts. See [README.md](README.md) for reproduction commands.

This was a bounded functional campaign. It did not exhaust build-compatible extensions, custom contexts, concurrent lifecycle interleavings, separate bean archives, or every possible event type. Most qualifier selections and generic events matched, and the 64 handle sequences matched; those results establish agreement for this corpus only. No production code was changed.
