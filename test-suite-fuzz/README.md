# Differential fuzzer against Weld

This module generates random CDI Lite deployments from a seed. It runs each one on micronaut-cdi and on the
reference implementation, Weld 7.0.0.Final (the CDI 5.0 implementation), and reports every place where the two
disagree.

## Running it

```
./gradlew :test-suite-fuzz:fuzz -Pfuzz.seed=1 -Pfuzz.count=200
```

| Property | Default | Meaning |
| --- | --- | --- |
| `fuzz.seed` | `1` | The first seed of the campaign |
| `fuzz.count` | `100` | How many consecutive seeds to run |
| `fuzz.replay` | | Run only this seed. Use it to reproduce a finding |
| `fuzz.size` | `medium` | `small`, `medium` or `large` scenarios |
| `fuzz.shrink` | `true` | Shrink each scenario that diverges |
| `fuzz.shrinkBudget` | `150` | The most runs one shrink may spend |
| `fuzz.keepAll` | `false` | Also write a report for the scenarios that agree |

The campaign never fails the build. Its results are written under `test-suite-fuzz/build/fuzz/`:

- `summary.md` gets one row per divergence for each campaign, followed by the totals.
- `seed-N/summary.txt` gives the outcome on each side, the divergence, and the CONFORMANCE.md heading if the
  difference is a known one.
- `seed-N/original/` and `seed-N/shrunk/` hold the scenario's sources (`src/`), both traces, each side's failure
  detail, and `diff.txt`, which puts the normalized traces side by side and marks the lines that differ.

The `shrunk/src` directory is the reproducer. Its classes are plain CDI: copy them into a test of `test-suite-java`
and assert on the trace that `Driver.run` returns.

`./gradlew check` runs only `HarnessTest`. It covers the fuzzer itself: the generator is deterministic and always
generates valid Java, the comparison ignores exactly what the specification leaves open, the shrinker reduces
correctly, and a simple scenario agrees end to end.

## How it works

1. **Generate.** `ScenarioGenerator` turns a seed into a `Scenario`, a plain model with these parts:
   - managed beans with a scope (`@Dependent` or `@ApplicationScoped`), service interfaces, bean superclasses,
     qualifiers (a marker, a binding member, a `@Nonbinding` member), `@Typed`, `@Alternative` and `@Priority`,
     and generic bean classes;
   - injection points: fields, constructors, initializers, `Instance<T>` and `Provider<T>`;
   - producer methods and fields, static or not and scoped or not, with disposers;
   - observers of class, subclass, generic and wildcard event types, of `Startup` and `Shutdown`, with
     priorities, qualifiers and `IF_EXISTS`;
   - lifecycle callbacks, interceptors (around-invoke, post-construct, pre-destroy, `@Inherited` bindings, bindings
     on the class or on a method, a bean's own `@AroundInvoke`), and decorators with `@Delegate` qualifiers;
   - probes, which the driver runs once the container is up: `select`, iterating an `Instance` (with
     `Instance.destroy` as an option), calling `describe()` on a bean with injection points, and firing an event
     through `BeanContainer.getEvent()`.

   The generator is tuned so that about 60% of scenarios deploy on Weld. The rest exercise definition errors and
   deployment problems on purpose.
2. **Render.** `ScenarioRenderer` writes the scenario as Java sources in a package unique to the run. The sources
   name only the CDI API. Every bean writes what happens to it to the scenario's own `Log` class: invocations,
   interceptor and decorator nesting, callbacks, observer notifications with their priority, productions,
   disposals and destructions. `Driver.run(SeContainer)` runs the probes, closes the container, and returns the log.
3. **Compile and run on both sides.** For micronaut-cdi, the sources are compiled with this repository's
   processors, because deployment happens at compile time. The classes are then loaded in a fresh class loader and
   started with `MicronautSeContainerInitializer`. For Weld, the same sources are compiled with plain `javac`
   and started with `new Weld().disableDiscovery().addBeanClasses(...)` in a class loader built only from the
   Weld class path. Weld never sees micronaut-cdi, and the two CDI providers never meet. If plain `javac` rejects a
   scenario, the generator has a bug, and the run is reported as a harness error rather than a finding.
4. **Compare.** `TraceComparison` sorts each divergence into one of these categories:
   - `DEPLOYMENT` (hard): one side deployed and the other refused.
   - `TRACE` (hard): both deployed, but the probes observed different things. The comparison ignores what the
     specification leaves open: the order inside an unordered stretch (`Instance` iteration, shutdown), and the
     order of observers that share a priority. Observers notified out of priority order still count as a
     difference.
   - `ORDER` (soft): the same events in a different exact order. This is often legitimate, so it is reported
     separately.
   - `REFUSAL_KIND` (soft): both sides refused, one with a definition error and the other with a deployment
     problem.
   - `CRASH` (hard): one side crashed or timed out (60 s).
5. **Filter known differences.** `KnownDifferences` holds one rule per intended difference recorded in
   CONFORMANCE.md, and each rule names its heading. A matching divergence is tagged `known`, not shrunk, and
   still listed.
6. **Shrink.** `Shrinker` is greedy. It tries removing half of the probes, then whole beans, interceptors,
   decorators and probes, then a bean's members, then single annotations. It keeps the first reduction that still
   diverges with the same signature (category plus the probe or side involved) and repeats until nothing reduces or
   the budget runs out. Element names never change while shrinking, so traces stay comparable.

## What it leaves out, and why

- **The request scope.** Its context is not active where the driver runs. The two containers' handling of that
  is not what the fuzzer is looking for.
- **Asynchronous events, build compatible and portable extensions, stereotypes, `@Named`, reserves and `@Eager`.**
  These are good next steps. Each needs its own probe and its own entries in the known-differences list.
- **`Object` in a producer's `@Typed`.** Weld checks a producer's `@Typed` against a set of bean types that has no
  `Object` in it. The fuzzer does not treat the reference implementation as authoritative on that point.
- **Observers of `Object` on non-static methods.** Weld recurses without end when it notifies one declared on a
  dependent bean that has callbacks.
