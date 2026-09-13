# Micronaut Core findings from building CDI Lite

What implementing the CDI Lite TCK on top of Micronaut surfaced about Micronaut itself: bugs found, fixes made in
the local checkout (`../micronaut-core`, published to mavenLocal), and API gaps worth taking upstream. Each entry
names where the behaviour lives and what this project does about it meanwhile.

## Bugs

### 1. A `@Primary` bean with another qualifier NPEs when resolved by its produced bean — FIXED upstream (#12945)
`BeanDefinitionWriter.getQualifier` maps `@Primary` to a literal null qualifier; composed with another qualifier
that null lands in the `Qualifiers.byQualifiers(...)` array and `CompositeQualifier.filterQualified` dereferences
it. Fixed in the local checkout by leaving `@Primary` out of composite qualifiers
(`core-processor/.../writer/BeanDefinitionWriter.java`); regression spec added at
`inject-java/src/test/groovy/io/micronaut/inject/cdiscenarios/PrimaryQualifiedFactorySpec.groovy`.

### 2. `AbstractConcurrentCustomScope.remove(BeanIdentifier)` closes the bean but never removes it — FIXED upstream (#12947)
`inject/.../context/scope/AbstractConcurrentCustomScope.java`: `remove` does `scopeMap.get(identifier)` +
`close()`, so the destroyed instance stays in the map and keeps being served — a client proxy resolves the
*destroyed* instance forever after. Found via the TCK's `AlterableContextTest`. Fixed locally with
`scopeMap.remove(identifier)`; this project also removes-then-closes in its own scopes
(`cdi/.../context/ApplicationScope.java`, `RequestScope.java`) so it works against the published snapshot too.

### 2a. Interceptor lifecycle, two fixes made for this project — FIXED upstream (#12920, #12922)
`@PreDestroy` methods were not invoked on a bean carrying `PRE_DESTROY` advice (#12920), and an interceptor
instance was not the same across a bean's construction, method interception and destruction (#12922). Both
matter to Jakarta Interceptors' lifecycle contract (one interceptor instance per intercepted instance, whose
`@PreDestroy` sees what its `@PostConstruct` acquired), which the CDI TCK's interceptor kit asserts.

### 3. `Qualifiers.byAnnotation(Annotation)` compares by type only, ignoring members — FIXED upstream
A bean qualified `@Chunky(true)` matched a lookup for `@Chunky(false)`. Merged into 5.2.x as
"Compare the members of a qualifier built from an annotation instance" (#12928). This project routes every
lookup through `CdiQualifiers`, which builds the qualifier from the annotation's values, so it was unaffected
either way.

### 4. `@Bean(typed = double.class)` — primitives rejected in exposed types — FIXED upstream
Merged into 5.2.x as "Allow a primitive type to be named among a bean's exposed types" (#12927). Also merged
alongside: "Fix an interceptor not matching a bean that leaves a binding member at its default" (#12925) —
binding members are now compared with `AnnotationValue.matches`, so a declared default and an omitted member
read as the same binding.

## API gaps and inconsistencies worth an upstream conversation

### 5. A field's `AnnotationMetadata` does not include its declaring class's annotations; a method's does
`FieldElement.getAnnotationMetadata()` sees only what the field declares, while producer-style resolution often
needs the class context (`@Priority` on the class selecting an `@Alternative` producer field). This project works
around it by consulting the declaring `ClassElement` explicitly (`ProducerVisitor.selectIfAlternative`). Either
behaviour is defensible, but methods and fields disagreeing is a trap.

### 6. `jakarta.annotation.Priority` is remapped to `@Order` with the value as-is — and the original is dropped
Two consequences: (a) code looking for `@Priority` in metadata finds nothing after mapping, so compile-time reads
must check both forms (`cdi-processor/.../Cdi.priorityOf`); (b) the sign convention inverts the meaning — CDI
priority prefers the highest value, Micronaut order the lowest — so a CDI-selected alternative must write
`@Order(-priority)` *over* the mapped value. A definition-level `getPriority()` (or documented mapping) would
remove the trap.

### 7. NOT REPRODUCED — `getAnnotationNamesByStereotype` names the immediate carrier, not the declared annotation
A javac probe on `Class → @S → @RequestScoped → @NormalScope` (in-source and precompiled `@S`, `@Inherited`
too) answers `byStereotype(NormalScope) = [RequestScoped]` — the immediate carrier, as
`MutableAnnotationMetadata` records `CollectionUtils.last(parentAnnotations)`. Only a *repeatable* stereotype
records the whole chain. `CdiScopeVisitor.resolveToScope` works either way, so nothing here was at risk; the
original text below is kept for the record.

#### 7 (original). `getAnnotationNamesByStereotype` returns the *declared* annotation for transitive chains
For `Class → @SomeStereotype → @RequestScoped → @NormalScope`, asking for annotations carrying `NormalScope`
returns `SomeStereotype`, not `RequestScoped` (`DefaultAnnotationMetadata.java:1133`, the
`annotationsByStereotype` index). Correct for "what did the author write", but callers expecting the stereotype's
*subject* must resolve through the annotation's own metadata (`CdiScopeVisitor.resolveToScope`). Worth
documenting; a `getAnnotationCarryingStereotype`-style API would express both needs.

### 8. `java.lang.annotation.*` meta-annotations are stripped from metadata — `@Inherited` is unqueryable
`hasDeclaredAnnotation(Inherited.class)` is always false. CDI's §2.3.1 inheritance rules turn on `@Inherited`, so
this project reads it off the native `javax.lang.model` element (`CdiScopeVisitor.inheritedOnTheSourceElement`,
unwrapping `ClassElement.getNativeType()` reflectively). An `ElementMetadata.isInherited()`-style accessor on
`ClassElement` would avoid the native unwrap.

RESOLVED upstream: `AnnotationElement.isInherited()` (`@since 5.2.0`) answers it for javac, KSP and Groovy; see #36.

### 9. No unfiltered, predicate-aligned view of the compiled definitions
CDI's `getBeans` must return *unresolved* candidates — a selected alternative and the bean it outranks together —
while every Micronaut lookup resolves as it goes (replacement filtering, primary narrowing).
`getBeanDefinitionReferences()` works as the raw view, but an environment that narrows the context via
`ApplicationContextBuilder.beansPredicate(...)` cannot read that predicate back from the context, so this project
carries the same predicate twice (`DeploymentBeanFilter`). Exposing the configured predicate on
`BeanContext`/`ApplicationContextConfiguration` would remove the duplication.

### 10. RETRACTED — `getBeanRegistration(BeanDefinition)` has existed since 3.5.0
Claimed that creating an instance *of a specific definition* with dependents tracked (CDI's `Bean.create` +
`CreationalContext.release`) needed a new overload, and pinned the definition with a custom `Qualifier`
filtering candidates by identity. `BeanDefinitionRegistry.getBeanRegistration(BeanDefinition<T>)` is marked
`@since 3.5.0` and does exactly this — `DefaultBeanContext` implements it as
`resolveBeanRegistration(null, beanDefinition)`, bypassing candidate reduction entirely, which is what the
hand-rolled qualifier was emulating. The finding was never checked against the API surface.

Three copies of that workaround (`CdiBean.thisDefinitionOnly`, `DisposerInvoker.registrationOf`,
`CdiObserverMethod.registrationOfDeclaring`) are gone in favour of the overload.

### 11. `@InjectScope` on a constructor/factory parameter never destroys — two compounding bugs — FIXED upstream (#12934)
(a) `BeanDefinitionWriter.hasInjectScope()` passes the constructor/factory `MethodElement` into the
`AnnotationMetadata` overload, checking the *method's own* annotations instead of its parameters — so
`destroyInjectScopedBeans()` is never emitted for constructor-parameter `@InjectScope` (field and
`@PostConstruct`-method paths do iterate parameters). (b) `DefaultBeanContext.findCustomScope` returns `null`
immediately for `@Prototype` definitions, so the injection-point-declared scope check further down never runs —
`@InjectScope` works for *unscoped* beans but silently does nothing for prototype ones (`@Singleton` correctly
outranks it per the annotation's javadoc; prototype should behave like unscoped). Found because CDI's
`@TransientReference` maps to `@InjectScope` and `@Dependent` maps to `@Prototype`: the TCK's
`DependentTransientReferenceDestroyedTest` never saw the destruction. Both fixed in the local checkout
(`core-processor/.../writer/BeanDefinitionWriter.java`, `inject/.../DefaultBeanContext.java` —
`findInjectionPointDeclaredScope` extracted and reached from the prototype branches).

### 12. `BeanResolutionCustomizer` has no say when an instantiation returns null — RESOLVED, no core change needed
A CDI producer may legitimately return null, and `DefaultBeanContext.resolveByBeanFactory` throws "returned
null" before `resolveNullBean` is consulted — unless the definition carries the `Nullable` stereotype, which
lets the null through to `resolveNullBeanRegistration` and the customizer's `resolveNullBean` (#12678). That
condition is the extension point: `ProducerVisitor` annotates every producer with the stereotype at compile
time (section 3.2.2 says a producer may return null), so no runtime hook is needed. A `shouldAllowNullBean`
customizer hook was tried locally and upstreamed as #12936, then withdrawn as redundant; the local patch is
removed. The proxy-target half of the story is separate and still needed: #12933 (finding #17).

### 13. The CDI hooks of #12678 work — notes from wiring them
`beanResolutionCustomizer` carried this project through generic-variable resolution (isCandidateBean), array
bean types (shouldResolveArrayAsBean), primitive boxing (resolveBeanLookupArgument), null producers
(resolveNullBean), and CDI's proxy-based circular-dependency breaking (shouldInitializeBean=false for client
proxies, shouldPreserveLazyProxyTargetResolutionPath=false). One trap: `BeanRegistration.close()` is a no-op
unless the definition is disposable or dependents exist — destruction that must fire
`BeanPreDestroyEventListener` (this project's disposer invocation) has to go through
`beanContext.destroyBean(registration)` instead.

### 13a. The `BeanRegistration.close()` no-op trap — FIXED upstream (#12943)
`BeanRegistration.of(context, ...)` returned a plain registration — whose `close()` is a no-op — unless the
definition had dispose logic, dependents, or was a `LifeCycle`; destruction listeners were silently skipped
for everything else. The local tree now always returns the disposing registration (closing destroys through
the context), matching the open upstream PR #12938. A custom scope's `destroyScope` closing its `CreatedBean`s
now reaches `BeanPreDestroyEventListener`s — which is what lets a request-scoped synthetic bean's disposal
function run when the request ends.

### 13b. Closing a registration twice destroyed the bean twice — FIXED upstream (#12956)
A consequence of 13a: once `BeanRegistration.of(context, ...)` always returned a `BeanDisposingRegistration`,
its `close()` ran `beanContext.destroyBean(this)` every time it was called, so a second `close()` ran
`@PreDestroy` a second time. CDI reaches this easily — a lookup destroys the dependent instances it created
when it is closed, and an `Instance.Handle` may already have destroyed one of them. `BeanDisposingRegistration`
now guards with an `AtomicBoolean`, so the first close destroys and later ones are no-ops.

This project keeps its own bookkeeping (removing a registration from the lookup's list before closing it, and
the handle's `destroyed` flag) because the non-registration path still goes through `destroyBean`, but
correctness no longer depends on that bookkeeping being perfect.

### 13c. `ExecutableMethod` exposes no modifiers — not worth asking core for
`ExecutableMethod` declares only `isAbstract()`/`isSuspend()`; asking whether a compiled method is public means
`getTargetMethod()`, which `AbstractExecutable` resolves through `ReflectionUtils.getRequiredMethod` +
`setAccessible(true)` (and `AbstractExecutableMethodsDefinition` logs it as "Reflectively accessing method").
So a compile-time-known fact cost a reflective lookup that throws `NoSuchMethodError` where the method is not
registered for reflection.

Not raised upstream: the answer is known while the disposer is compiled, so `@CdiDisposer` simply records it
(`publicMethod`). No core change, no metadata beyond one boolean on an annotation this project already writes.

### 14. `ThreadLocal` custom scope destroys beans only with `lifecycle = true` (not a bug — a doc trap)
`@ThreadLocal` beans are not destroyed on scope end unless `lifecycle = true` is set; the flag is easy to miss
and initially read as a dependent-destruction bug during this project (it was not — behaviour is by design and
documented on the annotation).

### 15. A static method meta-annotated `@Executable` got no `ExecutableMethod` — FIXED upstream (#12957)
Stated too broadly at first: a `static` method annotated **directly** with `@Executable` already worked —
`DeclaredBeanElementCreator` had a carve-out for it and `DispatchWriter` emitted `invokestatic`. The real gap
was narrower: the gate asked `hasDeclaredAnnotation(Executable.class)`, which is false for an annotation that
is itself meta-annotated `@Executable` — so essentially every executable annotation in the ecosystem
(`@Get`, `@Scheduled`, and this project's `@CdiObserver`) was silently dropped on statics. The gate now asks
for the `@Executable` **stereotype** on the method's own metadata, with adapter advice (`@EventListener`)
still excluded because it adapts an instance method and cannot apply to a static.

Adopted here: static observers are dispatched through their generated `ExecutableMethod` like any other, so
the class-level `@CdiStaticObservers` index, its signature encoding, `StaticObserverMethod`, and the
`@ReflectiveAccess` marking of static observers are all gone — roughly 200 lines of reflective dispatch, and
one fewer reason for this container to reflect at runtime. Observers needed the fix because `@CdiObserver`
carries `@Executable` as a meta-annotation.

Static **disposers** were a separate matter, and the fault was entirely ours: `ProducerVisitor` writes a
*direct* `@Executable` on a disposer, which already worked on statics before #12957. The reflective
`invokeStatic` in `DisposerInvoker` rested on the same over-broad belief and never needed to exist. It is
gone too, and `StaticDisposerTest` now covers the case — there is no static disposer anywhere in the TCK, so
that path had no test at all before.

### 16. Inherited executable methods keep the declaring class's unresolved type variables
An executable method declared on a generic superclass and inherited into a concrete subclass keeps the
superclass's `Argument`s: the type variables are erased to their bounds rather than substituted with the
subclass's actual type arguments (e.g. `AbstractObserver<T>.observe(T)` inherited by `FooObserver extends
AbstractObserver<Foo<String>>` reports `Object`, not `Foo<String>`). Visiting the abstract class can even
record erased metadata onto the shared method element, clobbering the subclass's view. This project rebuilds
the observed type reflectively (`getGenericParameterTypes()` + a substitution map walked from the concrete
class) — core substituting variables when copying inherited executable methods would remove that need.

### 17. `getProxyTargetBean` skips `resolveNullBeanRegistration` — FIXED upstream (#12951)
The lazy proxy target path (`DefaultBeanContext.getProxyTargetBean`) returns `registration.bean` directly, so a
factory/producer that legitimately returned null (allowed via `shouldAllowNullBean`) hands the proxy a null
target and the intercepted call throws NPE — while the ordinary lookup path routes the null through
`resolveNullBeanRegistration` where a customizer can substitute or throw (CDI throws
`IllegalProductException` for a non-dependent producer). Patched locally: both overloads now consult
`resolveNullBeanRegistration` when the resolved bean is null.

### 18. `@InjectScope` destruction misses pre-destroy listeners; the scope instance was JVM-global — FIXED upstream (#12934)
`DefaultCustomScopeRegistry.InjectScopeImpl.stop()` called `CreatedBean.close()`, which is a no-op for a
registration whose definition has no pre-destroy of its own (`BeanRegistration.of` only returns a disposing
registration for `DisposableBeanDefinition`/`LifeCycle`/dependents) — so `BeanPreDestroyEventListener`s (CDI's
disposer methods) never ran for `@InjectScope`-destroyed beans. Also `INJECT_SCOPE` was a `static final`
singleton with a mutable `currentCreatedBeans` list, shared by every `ApplicationContext` in the JVM. Patched
locally: one `InjectScopeImpl` per registry, and `stop()` destroys through the `BeanContext` so listeners run.

### 19. `@ClassImport` is never processed as a top-level trigger — FIXED upstream (#12952)
`BeanDefinitionInjectProcessor` filters the round's annotations with
`lookupOrBuildForType(ann).hasStereotype(ANNOTATION_STEREOTYPES)`; `ClassImport` is listed in that array, but an
annotation type never carries itself as a stereotype, so a class annotated only with `@ClassImport` (plus, say,
`@Generated`) is silently skipped and no imports happen. Additionally `ModelUtils.resolveTypeElements` drops any
element annotated `@Generated` — reasonable for Micronaut's own outputs, but it means a *generated* importer
class must not carry `@Generated`. Patched locally: the filter admits `ClassImport` by name. Found because this
project generates a `@ClassImport` source to turn extension-scanned classes (CDI `ScannedClasses.add`) into
beans.

### 20. No way to observe the resolution path from outside a resolution — RESOLVED upstream (#12937)
Nothing tells an integration *why* the bean it is constructing at runtime is being constructed — CDI 2.10.5
requires a synthetic (runtime-registered) dependent bean's creation function to see the `InjectionPoint` it is
being created for, but a `RuntimeBeanDefinition` supplier receives no `BeanResolutionContext`. Patched locally:
`AbstractBeanResolutionContext` keeps a thread-local deque of the contexts open on the calling thread — pushed
in the base constructor so both `DefaultBeanResolutionContext` and `DefaultBeanContext`'s
`SingletonBeanResolutionContext` register (a copy taken via `copy()` immediately deregisters: it is stored away
for later, not the resolution under way), popped in `close()` — exposed as static
`AbstractBeanResolutionContext.activeContexts()` (most recently opened first). CDI walks the active paths for
the segment whose argument matches the synthetic bean's type. A cleaner upstream shape might be passing the
resolution context (or the current segment) to `RuntimeBeanDefinition` suppliers directly.

### 21. `RuntimeBeanDefinition.Builder.singleton(boolean)` ignores its argument — FIXED upstream (#12946)
`DefaultRuntimeBeanDefinition.Builder.singleton(boolean isSingleton)` sets `this.singleton = true`
unconditionally, so `singleton(false)` still builds a singleton definition. A runtime-registered bean meant to
be prototype/dependent (CDI's synthetic beans default to `@Dependent`) is created once in the singleton scope,
is never tracked as a dependent of whoever asked for it, and never destroyed with it. Patched locally:
`this.singleton = isSingleton`.

### 22. Resolving from a stopped context throws `BeanContextException` rather than `IllegalStateException` — FIXED upstream (#12948)
`DefaultBeanContext.assertContextState` reports resolution against a context that is not running as a
`BeanContextException` extending plain `RuntimeException`. Using an object in the wrong lifecycle state is what
`IllegalStateException` means in the JDK's own vocabulary — and CDI requires exactly that of a contextual
reference used after the container shut down. Patched locally: `assertContextState` throws
`IllegalStateException`.

### 23. RETRACTED — the repeatable qualifier was dropped by this project, not by core
Recorded as core dropping a `@Repeatable` annotation from a method parameter's metadata. It does not: probing
`origin/5.2.x` directly shows a parameter keeps the container exactly as a field does. What dropped it was this
project's own `InjectedParameters.readAsInjectionPoints`, which strips the qualifiers a parameter did not
declare itself and asked the question two incompatible ways — `getAnnotationNamesByStereotype` answers with the
name the author wrote (`Start`), while the metadata declares the container (`Bootable`), so
`hasDeclaredAnnotation("Start")` said no and the removal took the container with it. Fixed by comparing against
the annotations written on the parameter, container members included.

The one real observation underneath it is a trap worth knowing: for a repeatable annotation,
`getAnnotationNamesByStereotype` reports a name that `hasDeclaredAnnotation` then denies, on every element
kind, and `getDeclaredAnnotationNamesByStereotype` reports nothing at all. Code that pairs those queries will
be wrong about repeatable annotations.

### 25. Mutations of an inherited parameter or field are cached per *owning type* — invisible through a subclass — PR #12971
`AbstractElementAnnotationMetadataFactory` keys parameter metadata as `lookupOrBuildForParameter(owningType,
method, parameter)` (`Key3`) and fields as `(owningType, field)`, although the parameter hierarchy is built from
overridden parameters plus the variable and never includes the owner. Probe: a visitor on `Base` that removed
`@Marker` from `Base.inherited(a)` and added `@Added` — a visitor on `Sub`, which inherits the method, still saw
`@Marker` and never saw `@Added`. Both removal and addition are lost across owners. This is the real reason the
project's static `RemovedAnnotations` registry exists (its javadoc blames visitor views; the probe shows the
owner split). Fix: drop `owningType` from the parameter and field keys. Compile-time only; zero runtime cost.

Measured here rather than assumed: `InheritedObserverEnhancementTest` removes `@Observes` from an inherited
method's parameter through the class that *declares* it and fires at the subclass that is the bean. It passes
with `RemovedAnnotations` consulted and **fails without it**, while the same-owner `FieldEnhancementTest` passes
either way — so the registry is load-bearing for exactly the cross-owner case #12971 fixes, and for nothing
else. When #12971 is merged and in a snapshot, `Cdi.declares` collapses to `hasDeclaredAnnotation` and
`RemovedAnnotations` (with its `reset()` calls in `BuildCompatibleExtensionVisitor`) can be deleted; that test
is what will prove it.

### 26. Repeatable annotations are invisible to the *declared* queries by name — PR #12963 (narrowed: only `getDeclaredAnnotationNamesByStereotype`; widening the by-name `has*` queries broke Kotlin data-class configuration metadata, which relies on "declared under its own name" meaning explicitly written)
`DefaultAnnotationMetadata.getDeclaredAnnotationNamesByStereotype` filters the stereotype index — which lists
the **member** (`Q`) — against `declaredAnnotations`, which holds the **container** (`Qs`) → always empty.
`hasDeclaredAnnotation(String)` likewise ignores the container while the `Class` overload maps through
`findRepeatableAnnotationContainerInternal`. Probe on `@Q("a") @Q("b")`: `byStereotype=[Q]`,
`declaredByStereotype=[]`, `hasDeclared(Q)=false`, `hasDeclared(Qs)=true`. Fix: accept `s` when
`declaredAnnotations` contains its container. Tiny, zero overhead. This is the trap behind retracted #23.

### 27. `AbstractConcurrentCustomScope` cannot answer "the instance held for this definition" — PR #12969 (also fixes `destroyProxyTargetBean` for `@ScopedProxy` beans on such a scope)
`CustomScope.findBeanRegistration(BeanDefinition)` has a default returning empty since 3.5; the abstract scope
implements only the `(T bean)` overload, `remove(BeanIdentifier)` is `final` and the identifier core stores
under (`DefaultBeanContext.BeanKey`) is package-private — so an `AlterableContext.destroy(Contextual)` has to
scan every `CreatedBean` and match proxy target *names* (`context/ApplicationScope.java`, `RequestScope.java`,
two ~40-line copies). Fix: implement `findBeanRegistration(BeanDefinition)` in the abstract scope and add a
non-final `remove(BeanDefinition)` that removes under the lock and closes outside it. Additive.

### 28. `AbstractConcurrentCustomScope.getOrCreate` holds the scope-wide write lock around `doCreate` — PR #12973 (opt-in `lockPerBean` constructor flag)
Every scoped bean's constructor and `@PostConstruct` in the JVM runs mutually exclusively per scope, even where
the scope hands back a per-request map. An application-scoped `@PostConstruct` that waits on another thread
creating another application-scoped bean deadlocks. The class javadoc admits it is for "a small amount of
beans". Fix: per-key creation (`computeIfAbsent`-style) with `doCreate` outside any lock, as an opt-in base or
flag. Medium; existing subclasses unchanged. micronaut-http's `RequestCustomScope` has the same exposure.

### 29. `DefaultCustomScopeRegistry` caches negative lookups forever — FIXED upstream (#12961)
`findScope` is `scopes.computeIfAbsent(name, …findBean…)` and stores `Optional.empty()` permanently;
`registerBeanDefinition` purges the candidate caches but never the scope registry. A `CustomScope` registered
at runtime (extension-declared contexts, `extension/ExtensionContexts.java`) is invisible if any bean of that
scope was resolved first — and such a bean silently becomes dependent. Works today only by eager-bean ordering.
Fix: `CustomScopeRegistry.invalidate()` (default method) called from `registerBeanDefinition` when the
definition's type is a `CustomScope`. Zero cost on resolution.

### 30. `RuntimeBeanDefinition.Builder` has no disposer hook — PR #12964
The builder offers qualifier/replaces/named/scope/singleton/exposedTypes/typeArguments/annotationMetadata;
`DefaultRuntimeBeanDefinition` is not a `DisposableBeanDefinition`. A synthetic bean's disposal function is
therefore run from a JVM-wide `BeanPreDestroyEventListener<Object>` plus an identity map
(`extension/SyntheticDisposerListener.java`, `SynthesisRunner.creatorLookups`). Fix: `Builder.disposer(
BiConsumer<BeanContext, B>)` making the built definition disposable. Additive, zero overhead. (Project side:
the creator lookup's transient registrations should go through `resolutionContext.addDependentBean` — core
already collects dependents into the registration.)

### 31. No API from a `ProxyBeanDefinition` to its target `BeanDefinition` — PR #12974
Only `getTargetDefinitionType()` (a `Class`) and `getTargetType()`; `getProxyTargetBeanDefinition(Argument,
Qualifier)` re-resolves by type. Six sites here match definition class names (`CdiBean.targetDefinition`,
`canonicalDefinitionName`, `CdiBeanContainer`, `CdiInstance.dedupProxies`, both scopes, `RecordedInvoker`);
core itself does the same internally. Fix: `ProxyBeanDefinition.findTargetDefinition(BeanDefinitionRegistry)`
or `BeanDefinitionRegistry.findBeanDefinition(Class<? extends BeanDefinition<?>>)`. Read-only, off the hot path.

### 32. A resolution segment's kind is only knowable through `@Internal` classes — PR #12970
`FieldSegment` implements `InjectionPoint`/`ArgumentInjectionPoint` but not `FieldInjectionPoint`, and its
`getOuterInjectionPoint()` throws `UnsupportedOperationException`, so `CdiInjectionPoint.of` must `instanceof`
`AbstractBeanResolutionContext.FieldSegment`/`ConstructorSegment` (`@Internal`). Fix: implement
`FieldInjectionPoint` (it has name, argument, declaring bean) and return `null` rather than throw. Zero overhead.

### 33. `destroyBean(Object)` for an untracked proxied bean drops its interceptor registrations — PR #12972
After #12922 a proxy's four interception phases share one interceptor instance, destroyed as a dependent of
the target — except through `destroyBean(T)`'s fallback (`DefaultBeanContext.destroyBean(T)`), which builds
`BeanRegistration.of(this, key, definition, bean)` with no dependents, so a `@PreDestroy` on the interceptor
does not fire there. Core's own lifecycle table lists this row as the exception. Fix: when
`bean instanceof Intercepted i` and `i.$interceptorRegistrations()` is non-empty, pass the non-singleton ones
as dependents. Only that fallback path is touched. Relevant to micronaut-jakarta-interceptors, whose weak-map
per-target bookkeeping #12922 otherwise made redundant.

### 34. `MethodArgumentSegment.getOuterInjectionPoint()` throws for a plain `@Inject` method argument
Found while fixing #32: the segment's `outer` is only set when the previous segment happens to be a
`MethodSegment`, and every production caller pushes method arguments through the
`(BeanDefinition, String, Argument, Argument[])` overload with no `MethodInjectionPoint` at hand — so `outer` is
absent and the accessor throws `IllegalStateException("Outer argument inaccessible")`. After #32 a customizer can
tell a field (`instanceof FieldInjectionPoint`) and a constructor argument (`outer instanceof
ConstructorInjectionPoint`) apart, but still cannot call `outer` safely on a method argument. Fix: return `null`
as `FieldSegment` now does, or carry the method injection point on that overload. Zero overhead.

### 35. `SingletonScope.getOrCreate` releases its per-identity lock after a *failed* creation
Observed while implementing #28: `SingletonScope` removes the per-identity lock object in `finally`, so when a
creation throws, two waiters that arrive afterwards can each create under a fresh lock and both succeed — two
instances of a singleton. `AbstractConcurrentCustomScope`'s new per-bean mode deliberately keeps its lock
objects for the scope's life to avoid exactly this. Unverified by a test; recorded for a reproduction.

## Project-side follow-ups the same audit produced (not core's)
- Replace the static cross-visitor registries with `VisitorContext` attributes (`MutableConvertibleValues`,
  one context per compilation) and seed the TCK adapter's extensions through a `JavaParser` subclass rather than
  `BuildCompatibleExtensionVisitor.overrideExtensions`.
- `InjectedParameters.readAsInjectionPoints` rests on a premise core does not have (a parameter's metadata does
  not carry its method's annotations) — the removal loop removes nothing in the ordinary case; delete it.
- `@Executable(processOnStartup = true)` on `CdiObserver` + `ObserverRegistry implements
  ExecutableMethodProcessor<CdiObserver>` replaces the all-definitions walk; `CdiBeanContainer.canonicalBean`
  wants a map; `Class.forName` for annotation types → `AnnotationMetadata.getAnnotationType(name)`.
- Every normal-scoped bean also carries the `CdiApplicationScope` stereotype (`NormalScopeAnnotationMapper`);
  the runtime picks the right scope by registration order alone — needs a regression test.
- Under Groovy the holder classes `BuildCompatibleExtensionVisitor` generates (`ExtensionContextRecordHolder`,
  `ScannedClassesImport`) are never compiled: the Groovy compiler has no processing rounds, so a source file
  written through `visitGeneratedSourceFile` mid-compilation is not part of the compilation. Discovery's context
  records, registered qualifiers and scanned-class imports are lost there (`test-suite-groovy` compiles and
  passes only because it asserts none of them). **Fixed in core by micronaut-core#13179 (open, 5.3.x)**, which
  compiles generated sources in the same Groovy compilation. Verified 13 Sep 2026 against `5.3.0-CDICHECK`
  (5.3.x + 5.2.x + #13165 + #13179): both holders are compiled into the Groovy suite's output and the
  extension-registered `@Zesty` is a qualifier; the full CDI `check` passes on that build — TCK 807/807, `cdi-tck`
  46, `test-suite-java` 125 (1 deliberately disabled), Kotlin 1, Groovy 3, `cdi-el` 3, language-model kit 16 of 18
  with the two accepted deviations. `ModelUnderGroovyTest.anAnnotationTheDiscoveryPhaseRegisteredIsAQualifier`
  is pending until #13179 lands and enforces itself from then on.
- Interceptors bridge: done on its `main` — the advice holds the interceptor registrations and destroys them from
  its own `@PreDestroy`, interceptor classes are found through one index, and private interceptor methods are
  documented as accepted. What is left is core's: a scoped proxy's method interception keeps the interceptor
  instances resolved for the proxy while each target's lifecycle gets its own (see `RequestScopedInterceptorStateTest`).

### 24. No public way to read an annotation instance as an `AnnotationValue`
`Qualifiers.byAnnotation(Annotation)` (#12928) reads the members off a live annotation and stores them the way
compiled metadata does — a class as `AnnotationClassValue`, an enum by name, a nested annotation as an
`AnnotationValue`, `@NonBinding` members left out — in `AnnotationMetadataQualifier.fromAnnotation` /
`resolveAnnotationBindingValues` / `asMemberValue`. All three are private.

CDI hands the container annotation instances constantly — `Instance.select(Annotation...)`,
`Event.select(...)`, `BeanContainer.isMatchingBean(Set<Annotation>)`, the qualifiers an extension puts on a
synthetic bean — so this project re-implemented the same reader (`CdiAnnotations.valueOf`/`storedForm`, and
again in `SynthesisRunner`). The copy had drifted: it did not convert a nested annotation member, so a qualifier
such as `@Located(region = @Region("east"))` selected by its literal was **unsatisfied** — the live `Region`
proxy never equalled the stored `AnnotationValue`. Fixed here (`NestedAnnotationQualifierTest`), by mirroring
core's conversion.

A public `AnnotationValue.of(Annotation)` (or an `AnnotationUtil` equivalent) is zero-overhead compile-time-free
API that core already has the body of; it would remove the duplicates downstream and, more to the point, the
drift between them.

### 16. RETRACTED — inherited executable methods do resolve their type variables
Recorded as an inherited `@Executable` method keeping the declaring class's unresolved variables. On
`origin/5.2.x` it does not: `JavaMethodElement.getDeclaringType()` resolves the declaring superclass through
the owning subclass's type arguments, and every shape probed — deep chains, interface defaults, precompiled
superclasses, two subclasses in one round, AOP proxies, bridge methods, recursive and intersection bounds, and
the Groovy path — reports the substituted type. The visit-order clobbering half is not reproducible either.

What remains true is narrower and is a different gap: a wildcard degrades to its bound
(`Foo<?>` is compiled as `Foo<Object>`, `List<? extends Number>` as `List<Number>`), because
`io.micronaut.core.type.Argument` has no wildcard representation. That is why this project still rebuilds an
observed type reflectively — for wildcards alone, not for inheritance. Fixing it upstream means extending the
runtime `Argument` model, which is a larger, API-breaking change than the processor-side substitution first
assumed.

## The CDI Lite language model on the AST — inventory and design (12 Sep 2026)

`cdi-processor/.../extension/` hands a build compatible extension the language model of CDI 4.0 §2.10
(`jakarta.enterprise.lang.model`): `ElementClassInfo`, `ElementMethodInfo`, `ElementFieldInfo`,
`ElementParameterInfo`, `ElementPackageInfo`, `ElementAnnotationInfo`/`ElementAnnotationMember`, `ElementTypes`,
`VisitorTypes`. The kit's language model part (`:micronaut-cdi-tck-lang-model:test`, 1263 assertions, the sources
unpacked under `cdi-tck-lang-model/build/generated/tck/org/jboss/cdi/lang/model/tck/`) passes, but only because
`ExtensionSourceModel.sourceOf(element)` unwraps the `javax.lang.model.element.Element` behind every Micronaut
element (reflectively, through the `element()` accessor of `JavaNativeElement`) and `MirrorTypes`,
`MirrorAnnotationInfo`, `MirrorAnnotationMember` and `ExtensionAnnotations` read javac's mirrors from there. Every
such read is a place where a Groovy or KSP compilation gets the AST-only fallback, which today answers less.

What follows was verified against core `v5.2.1` (`git show v5.2.1:<path>` in `../micronaut-core`; paths below
are relative to that tree) and the `inject-java`, `inject-kotlin` and `inject-groovy` implementations, not against
the javadoc alone. The short version: the AST already answers far more than the package uses — type-use
annotations on declared types, type arguments, super types, thrown types, receivers and type-variable declarations
are all reachable — and the remaining gaps are five, three of them small. Finding #8 above is closed upstream by
`AnnotationElement.isInherited()` (`@since 5.2.0`, implemented for javac, KSP and Groovy).

### 36. Inventory of the javac reach-ins and what `io.micronaut.inject.ast` 5.2.1 answers

Legend: **AST** = answerable from the AST in all three languages today; **AST(J)** = answerable, javac only;
**gap N** = needs the core change in finding N below.

| Reach-in (`cdi-processor/.../extension/`) | Model question (javadoc / kit section) | AST 5.2.1 | Status |
|---|---|---|---|
| `ExtensionSourceModel.sourceOf`, `unwrap`, `elementUtils` (reflective `getElements()` on the context) | the seam itself | — | goes away with the rest; `JavaNativeElement` is `@Internal` and its shape changed once already (the `element()` holder), which is why the unwrap is reflective |
| `ElementClassInfo.isKind(...)` for `isInterface/isEnum/isAnnotation/isRecord` | `ClassInfo.isAnnotation()` etc.; `Equality`, `AnnotationMembers`, `InterfaceMembers`, `EnumMembers`, `PlainClassMembers` | `isInterface()`; `element instanceof EnumElement` (or `isEnum()`); `element instanceof AnnotationElement` — `JavaElementFactory.newClassElement(TypeElement)` switches on `ENUM`/`ANNOTATION_TYPE`, `KotlinElementFactory` and `GroovyElementFactory` do the same; `isRecord()` (`JavaModelUtils.isRecord`, `ClassNode.isRecord()`) | **AST**; KSP answers `isRecord()` false for a Java record on its classpath (no `ClassKind` for it) — see #44 |
| `ElementClassInfo.isAbstract()` (enum declaring abstract methods) | `ClassInfo.isAbstract()`; `EnumMembers` asserts `clazz.isAbstract()` | `isAbstract()` mirrors the modifier in all three (`JavaClassElement.isAbstract`, KSP `declaration.isAbstract()`, `ClassNode.isAbstract()`), so it is false; but `isEnum() && !getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared().onlyAbstract()).isEmpty()` answers it — `getElements` passes `includeAbstract = true` for an `onlyDeclared` query | **AST** (no core change needed; optional #43c) |
| `ElementClassInfo.typeParameters()` → `TypeElement.getTypeParameters()` + `MirrorTypes.ofParameter` | `ClassInfo.typeParameters()` "type parameters *declared*"; `AnnotatedTypes.verifyTypeParameters`, `AnnotatedSuperTypes` | `ClassElement.getDeclaredGenericPlaceholders()` → `GenericPlaceholderElement.getVariableName()`, `getBounds()` (an `IntersectionType` is split into one bound each, `AbstractJavaElement.resolveTypeVariable`), `getGenericTypeAnnotationMetadata()` (`JavaElementAnnotationMetadataFactory.lookupTypeAnnotationsForGenericPlaceholder` reads the `TypeParameterElement`'s annotations when the variable mirror carries none — the `TYPE_PARAMETER` ones) | **AST** for javac and KSP (`KotlinClassElement.internalDeclaredGenericPlaceholders` via `resolveTypeParameter`); Groovy returns `getBoundGenericTypes()` cast to placeholders (`GroovyClassElement.java:712`) — #43b |
| `ElementMethodInfo.typeParameters()` → `ExecutableElement.getTypeParameters()` | `MethodInfo.typeParameters()`; `AnnotatedTypes.verifyTypeVariableMethod`, `AnnotatedThrowsTypes` | `MethodElement.getDeclaredTypeVariables()` — javac from `getTypeParameters()`, KSP from `declaredTypeArguments`, Groovy from `methodNode.getGenericsTypes()` | **AST** |
| `ElementClassInfo.superClass()` / `superInterfaces()` → `getSuperclass()` / `getInterfaces()` mirrors | `ClassInfo.superClass()` "with annotations and type arguments as written in `extends`"; `AnnotatedSuperTypes` | `getSuperType()` / `getInterfaces()` build the element from the extends-clause mirror (`JavaClassElement.java:400-416`, `:375-393`), so `getTypeAnnotationMetadata()` holds `@AnnSuperClass` and each `getBoundGenericTypes()` entry keeps its own mirror and annotations; `getSuperType()` is empty for `Object`, which `superClassDeclaration()` already synthesises. KSP: `declaration.superTypes.map { it.resolve() }` keeps the `KSType` and `KotlinElementAnnotationMetadataFactory.lookupTypeAnnotationsForClass` reads `kotlinType.annotations`; Groovy reads `ClassNode.getTypeAnnotations()` | **AST** |
| `ElementMethodInfo.returnType()` (method) → `MirrorTypes.ofDeclared(source.getReturnType(), source)` | `MethodInfo.returnType()` with the declaration's own type variables; `AnnotatedTypes.verifyVoidMethod`, `BridgeMethods`, `Equality` | `getReturnType()` is `returnType(Collections.emptyMap())` (declaration view, variables stay placeholders), `getGenericReturnType()` substitutes; the returned element keeps the mirror → `getTypeAnnotationMetadata()`; `void` is `PrimitiveElement.VOID` with no annotations, which is what the kit asserts | **AST** for class types and type variables; **gap #38** for an annotated primitive; **gap #39/#41** because javac's `returnType(...)` also *adds* `@org.jspecify.annotations.NonNull` to the type annotations under `@NullMarked` (`JavaMethodElement.returnType`, `JavaFieldElement.getType`) and an unannotated use of a type variable reports its declaration's annotations |
| `ElementMethodInfo.returnType()` (constructor) → `MirrorTypes.ofConstructorReturn` filtering the constructor's annotations by `TYPE_USE` | `MethodInfo.returnType()` of a constructor is the class, carrying the annotations written before its name that *may* target a type use; `AnnotatedTypes.verifyConstructor` (`@AnnConstructor` is `CONSTRUCTOR`+`TYPE_USE`) | the class: `ElementTypes.of(getDeclaringType())`; the filter needs each annotation interface's `@Target`, which no language's metadata records: `@Target`, `@Repeatable` and `@Retention` are in `AnnotationUtil.INTERNAL_ANNOTATION_NAMES`, which `AbstractAnnotationMetadataBuilder.annotationMirrorToAnnotationValue` filters *before* the per-language `isExcludedAnnotation` hook gets a say (the javac and Groovy hooks lift the `java.lang.annotation.*` exclusion for an `ANNOTATION_TYPE` element, but only for names outside that list) | **gap #40** in all three languages |
| `ElementMethodInfo.receiverType()` → `ExecutableElement.getReceiverType()` | `MethodInfo.receiverType()`: null for static methods and non-inner constructors, the declaring type otherwise, with the annotations of a written receiver; `AnnotatedReceiverTypes` | `MethodElement.getReceiverType()` — javac returns the written receiver (with mirror, so annotations) and *empty* when none was written (`JavaMethodElement.getReceiverType`), although the interface javadoc promises "derived from the declaring type"; KSP and Groovy have no override → always empty. The model's default is answerable: `isStatic()`, `instanceof ConstructorElement`, `getDeclaringType().isInner() && !getDeclaringType().isStatic()` (`isInner()` is `NestingKind.isNested()`, so the static check is needed) | **AST**; #42 records the javadoc/implementation mismatch |
| `ElementMethodInfo.throwsTypes()` → `getThrownTypes()` mirrors | `MethodInfo.throwsTypes()` with annotations; `AnnotatedThrowsTypes` | `MethodElement.getThrownTypes()` — javac keeps the mirrors (`@AnnThrows1 Exception`, `@AnnThrows2 E` as a placeholder with `getGenericTypeAnnotationMetadata()`); Groovy from `methodNode.getExceptions()` (no type annotations on a `ClassNode` in a throws clause — unverified); KSP from `@Throws(exceptionClasses)` (Kotlin has no throws clause; annotations on it do not exist) | **AST** |
| `ElementFieldInfo.type()`, `ElementParameterInfo.type()` → `MirrorTypes.ofDeclared(source.asType(), source)` | `FieldInfo.type()` / `ParameterInfo.type()` with type-use annotations at every depth; `AnnotatedTypes.verify{Class,Array,Primitive,Parameterized,TypeVariable}Field`, `verifyWildcardMethod`, `EnumMembers.verifyConstructors` | `getType()` (declaration view) keeps the use's mirror in `JavaNativeElement.Class.typeMirror`; every type argument is built from its own mirror (`AbstractJavaElement.resolveTypeArguments` → `newClassElement(getNativeType(), typeParameterMirror, ...)`), so `@AnnParameterizedField2 Map<@AnnParameterizedField3 String, @AnnParameterizedField4 A>` is fully annotated through `getTypeArguments()`/`getBoundGenericTypes()` + `getTypeAnnotationMetadata()`; a wildcard is a `JavaWildcardElement` with `getUpperBounds()`/`getLowerBounds()`/`hasExplicitUpperBound()` and `getGenericTypeAnnotationMetadata()` from the `WildcardType` mirror; the unbounded `?` gets `Object` from `getTypeElement(Object).asType()` (no annotations) and `? extends @A Object` keeps the bound mirror; `isRawType()` tells `List` from `List<E>`. KSP: `KotlinTypeArgumentElement`/`KotlinWildcardElement` with the same accessors; Groovy: `GroovyWildcardElement`/`GroovyGenericPlaceholderElement` | **AST** for class, parameterized, wildcard and type-variable uses; **gap #37** arrays; **gap #38** primitives (`EnumMembers` asserts two type annotations on a `boolean` parameter) |
| `ExtensionAnnotations.declaredOn` → `Element.getAnnotationMirrors()` + `Elements.getElementValuesWithDefaults` | `AnnotationTarget.annotations()`: only `RUNTIME`-retained, as written (a repetition is itself, a container the source wrote is the container), members defaulted; `LangModelVerifier.ensureOnlyRuntimeAnnotations`, `RepeatableAnnotations`, `AnnotationInstances.verifyDefaultValues`, every `annotations().size()` | retention: `VisitorContext.getAnnotationRetentionPolicy(name)` (already used; implemented by all three builders; compile-time metadata records annotations of every retention, the class-file writer drops `SOURCE` later). Defaults: `AnnotationValue.getDefaultValues()` is attached at build time (`AbstractAnnotationMetadataBuilder.addDefaults`) but through `readAnnotationDefaultValues(name, type)` with `includeEmptyValues = false`, so `String x() default ""` has no default there; `VisitorContext.getAnnotationDefaultValues(name)` (`@since 5.1.0`) passes `true`. As written: **not answerable** — `MutableAnnotationMetadata.addDeclaredRepeatable` folds a single `@AnnRepeatable("single")` into `AnnRepeatableContainer`, and `getDeclaredAnnotationNames()` names the container (finding #26 circled this); mappers/remappers/transformers and `annotate()` calls (Micronaut's `@Priority`→`@Order`, jspecify `@NonNull`) are indistinguishable from source annotations | **gap #39** |
| `MirrorAnnotationMember.asType()` / `asEnumClass()` / `asEnumConstant()` | `AnnotationMember` kinds; `AnnotationInstances` | a class member is an `AnnotationClassValue` by binary name (`MetadataAnnotationValueVisitor.visitType`: declared types and primitives; an array class literal such as `String[].class` is **dropped** — #43a) → `context.getClassElement(name)`; an enum member is the constant's simple name (`visitEnumConstant`), its enum type is the annotation interface's member return type: `annotationElement.getEnclosedElements(ElementQuery.ALL_METHODS.named(member))` → `getReturnType()` (array members: `fromArray()`); nested annotations are `AnnotationValue`s; `byte`/`short`/`char` are boxed as such | **AST** (KSP's `readAnnotationValue` value classes for byte/short need checking — #44) |
| `ExtensionAnnotations.isInherited` → `@Inherited` on the annotation interface | `ClassInfo.annotations()` adds `@Inherited` superclass annotations, nearest first, none from interfaces; `InheritedAnnotations`, `RepeatableAnnotations` (inherited repetitions) | `((AnnotationElement) context.getClassElement(name)).isInherited()` — javac, KSP, Groovy. Do **not** use `ClassElement.getAnnotationNames()` for the inherited view: `JavaAnnotationMetadataBuilder.buildHierarchy` → `NativeElementsHelper.populateTypeHierarchy` includes *interfaces*, so `@AnnInherited5` on `AnnotatedSuperInterface` would be reported; walk `getSuperType()` with `getDeclaredAnnotationNames()` instead, as `ElementClassInfo.annotations()` already does | **AST** |
| `ExtensionAnnotations.containerOf` → `@Repeatable` on the annotation interface | `repeatableAnnotation(...)` must find repetitions inside the container; `RepeatableAnnotations`, `EnumMembers` | not recorded in any language (`@Repeatable` is in `INTERNAL_ANNOTATION_NAMES`, see the row above). Every builder computes it (`findRepeatableContainerNameForType`, protected; `KotlinVisitorContext.getRepeatableContainerNameForType` handles the `@JvmRepeatable` typealias) but nothing public exposes it. What the AST *does* answer without the name: `getDeclaredAnnotationValuesByName(annotation)` resolves the container internally and returns the repetitions, and a container is recognisable by shape (a `value` array of nested annotations of one interface), which is what `AstSourceModel`/`ExtensionAnnotations.repeatableIn` go by | **gap #40** for the name; repetitions **AST** |
| `ExtensionAnnotations.isTypeUse` → `@Target` | see constructor return type | as above | **gap #40** |
| `ExtensionSourceModel.typeOf/classOf` (class member → `Type`) | `AnnotationMember.asType()` | `ExtensionAnnotationTypes.declarationOf(name)` + `ElementTypes.of`, primitives via `PrimitiveElement.valueOf`, arrays via `toArray()` | **AST** |
| `ElementPackageInfo` (no direct reach-in; `declaredOn(PackageElement)` reaches javac through `sourceOf`) | `PackageInfo.annotations()`; `LangModelVerifier.verifyPackageAnnotation` | `JavaClassElement.getPackage()` returns a `JavaPackageElement` whose metadata is built from `package-info` (`lookupForPackage`); Groovy `GroovyPackageElement` from the `PackageNode` likewise; KSP uses the `ClassElement.getPackage()` default, `PackageElement.of(name)` — a `SimplePackageElement` with no metadata | **AST** javac/Groovy; KSP cannot (#44) |
| not a reach-in but relied on: `ElementMembers`, `ElementDeclarationInfo.equals/hashCode`, constructors, bridge methods | `ClassInfo.methods()/fields()` every declaration of the hierarchy with the declaring class kept apart; `InheritedMethods`, `InheritedFields`, `BridgeMethods`, `DefaultConstructors`, `JavaLangObjectMethods`, `Equality` | already AST-only on this branch: `ElementQuery.ALL_METHODS.onlyDeclared().includeOverriddenMethods().includeHiddenElements()` per raw class of the hierarchy; `Element.equals` is by native element (`AbstractJavaElement.equals`; KSP `KotlinClassNativeElement.equals` also compares the `KSType`, which is null for every element `getClassElement(name)` builds, so two readings of one class are equal); javac never hands out bridge methods for a class under compilation and Groovy filters `isSynthetic()` (`GroovyClassElement.java:801`); the implicit default constructor is in javac's enclosed elements and KSP's `primaryConstructor` | **AST** |
| `CdiScopeVisitor.inheritedOnTheSourceElement` (outside the package, finding #8) | §2.3.1 `@Inherited` | `AnnotationElement.isInherited()` | **AST** since 5.2.0 |

Two facts the table leans on, both verified in `inject-java`: (1) a `ClassElement` built for a *use* of a type
carries the use's `TypeMirror` in `JavaNativeElement.Class.typeMirror` and `getTypeAnnotationMetadata()` is
built from exactly that mirror's `getAnnotationMirrors()` (`JavaElementAnnotationMetadataFactory.
lookupTypeAnnotationsForClass` → `new AnnotationsElement(typeMirror)`), while an element from
`context.getClassElement(name)` has a null mirror and answers `getTypeAnnotationMetadata()` from the declaration;
(2) `getAnnotationMetadata()` of a use is the hierarchy of the declaration's annotations *and* the type
annotations (`JavaClassElement.getAnnotationMetadata`), so the model must read `getTypeAnnotationMetadata()` for a
type and `getDeclaredAnnotationNames()` on a freshly resolved declaration for a declaration, never mix them.

### 37. Core gap — a type annotation on one dimension of an array is not representable
`String[] @A1 [][] @A2 [][] @A3 [] f` (`AnnotatedTypes.verifyArrayField`) needs the annotations of each
dimension. `AbstractJavaElement.newClassElement` recurses through an `ArrayType` passing the *current* array
mirror down, so the leaf `JavaClassElement` is created with the innermost `String[]` mirror and every
`toArray()` above it copies that mirror unchanged (`JavaClassElement.withArrayDimensions` only swaps to the
component when going *down*). On top of that `JavaElementAnnotationMetadataFactory.lookupTypeAnnotationsForClass`
deliberately answers an array's type annotations with its **component's** unless a JSpecify annotation is present
("Backward compatibility for Micronaut type annotations support"). `ArrayableClassElement` has no per-dimension
notion; KSP models `Array<@A String>` as a type argument so the information exists there; Groovy's
`ClassNode.getComponentType()` chain has a `getTypeAnnotations()` per node but `newClassElement` drops to
`PrimitiveElement`/`.toArray()` without it.

Not pursued: one annotation set per array type, standing for the component's, is how Micronaut's model reads an
array, and a per-dimension record would exist only for the kit. `AnnotatedTypes.verifyArrayField` stays pending,
as it is skipped by other implementations.

### 38. Core gap — a type annotation on a primitive is dropped — MERGED upstream (#13164, 5.3.x; javac and Groovy)
`@AnnPrimitiveField int primitiveField` (`AnnotatedTypes.verifyPrimitiveField`) and the two type annotations on
`boolean disambiguate` / `int disambiguate` (`EnumMembers.verifyConstructors`). `newClassElement` maps a
`PrimitiveType` to the shared `PrimitiveElement.valueOf(kind)` constant (`AbstractJavaElement.java`, the
`PrimitiveType pt` branch; Groovy `ClassHelper.isPrimitiveType` → `PrimitiveElement.valueOf(classNode.getName())`),
which has `AnnotationMetadata.EMPTY_METADATA` and the default `getTypeAnnotationMetadata()`. `PrimitiveElement.
withAnnotationMetadata(AnnotationMetadata)` already returns an annotated copy; nothing calls it for a type use.

Proposal (core-processor + javac + Groovy, ~60 lines, additive): when `pt.getAnnotationMirrors()` is non-empty,
return `PrimitiveElement.valueOf(name, doc).withAnnotationMetadata(builder.lookupOrBuild(pt, new
AnnotationsElement(pt)).getAnnotationMetadata())`, and let `PrimitiveElement.getTypeAnnotationMetadata()` expose
the same metadata read-only. Keep `PrimitiveElement.equals`/`hashCode` on name and dimensions so the annotated
copy still equals the constant (the `Equality` section compares types by what they are). KSP is different: `AbstractKotlinElement.newClassElement` sets `canBePrimitive = type.annotations.isEmpty() && !isMarkedNullable`,
so an annotated `Int` is already a *class* element for `kotlin.Int` carrying the annotations — the model would
report `java.lang.Integer` where the source meant `int`. Making it a primitive changes `isPrimitive()` for every
annotated Kotlin primitive parameter and therefore the written `Argument` types: a behaviour change, to be
discussed with the Kotlin maintainers rather than slipped in.

### 39. Not a core change — the annotations as the source wrote them are derived, not recorded
Three things the model promises are not in the metadata record, by design of the record: (a) a repeatable
annotation written once is folded into its container (`MutableAnnotationMetadata.addDeclaredRepeatable`); (b)
what Micronaut itself adds — mapper and remapper output, the jspecify `@NonNull` that `JavaMethodElement.returnType`
and `JavaFieldElement.getType` write into a type's annotations under `@NullMarked` — is indistinguishable from
what the source wrote; (c) an annotation interface's own meta-annotations (`@Retention`, `@Target`,
`@Repeatable`) are `INTERNAL_ANNOTATION_NAMES` and never reach its metadata.

A parallel "as written" record in core (a `getSourceAnnotations()` view, with per-dimension javac array mirrors
and a use-versus-declaration marking for type variables) was prototyped as PR #13163 and **rejected**: it exists
only to reproduce the kit's exact-match assertions, while Micronaut's record is deliberately richer and
differently shaped. The model derives the specification's view from the record instead, and lets a deployment
choose how much of Micronaut's own to show:

- a container holding exactly one repetition is reported as the repetition (`ExtensionAnnotations.
  unfoldSingleRepetitions`, keyed on `getDeclaredAnnotationValuesByName` answering once for the repetition's
  name). Reflection reports a single repetition the same way; a container the source wrote around one repetition
  reads the same, which is the one shape not told apart. Covers the single and inherited cases of
  `RepeatableAnnotations` and the declaration half of `EnumMembers`. The mixed case — a repetition written beside a
  hand-written container, folded into one container of three — stays a deviation, accepted.
- an annotation interface reports the `@Retention` it declares, synthesised from `VisitorContext.
  getAnnotationRetentionPolicy`: `RUNTIME` and `SOURCE` are only ever declared, `CLASS` is what an undeclared
  retention is and is left out (`AstSourceModel.addRetention`). Covers `AnnotationMembers`. `@Target` and
  `@Repeatable` follow the same way once #40 lands.
- what Micronaut writes into its record is reported by default — the Micronaut way — and narrowed by any
  `io.micronaut.cdi.processor.extension.LanguageModelAnnotationFilter` registered as a service (all registered
  filters must agree). The kit module registers `SpecificationAnnotationFilter`, which leaves out Micronaut's
  annotation packages and, on a type use in a null-marked class or package, anything under the non-null stereotype
  (the synthesised marker arrives remapped, as `jakarta.annotation.Nonnull`). `ModelAnnotationsTest` pins the
  default; the kit pins the filtered view.

What neither derivation nor filter restores: a remapped annotation's original name (`@Priority` → `@Order`,
finding #6; jspecify `@NullMarked` → `io.micronaut.core.annotation.NullMarked`). That is a question of whether
remappers should keep the original beside the replacement, to be raised on its own.

### 40. Core gap — `AnnotationElement` knows `isInherited()` but not its targets, container or retention — MERGED upstream (#13162, 5.3.x)
`AnnotationElement` (`core-processor/.../ast/AnnotationElement.java`, `@since 3.1.0`) is the natural home for
the three other facts the model needs about an annotation *interface*, all of which every builder already
computes privately and none of which the metadata records in any language (`@Target`, `@Repeatable` and
`@Retention` are `INTERNAL_ANNOTATION_NAMES`, filtered before the per-language exclusion hook). Proposal
(additive, ~60 lines core + ~40 per language):

```java
/** The element types the interface may be written on, as its @Target (or kotlin.annotation.Target) declares
 *  them, mapped to java.lang.annotation.ElementType; JLS 9.6.4.1's default set when it declares none.
 *  @since 5.3.0 */
default Set<ElementType> getTargets()
/** The annotation interface holding this one's repetitions, by binary name; empty when not repeatable
 *  (java.lang.annotation.Repeatable, kotlin.annotation.Repeatable and @JvmRepeatable alike). @since 5.3.0 */
default Optional<String> getRepeatableContainer()
/** @return the interface's retention; RUNTIME when it declares none. @since 5.3.0 */
default RetentionPolicy getRetentionPolicy()
```

As merged, `getRetentionPolicy()` answers `RUNTIME` for an interface that declares no `@Retention` — Micronaut's
long-standing builder convention (`JavaAnnotationMetadataBuilder.getRetentionPolicy` falls through to
`RUNTIME`), deliberate because treating such annotations as `CLASS` would drop them from runtime metadata — so
"declares none" and "declares `RUNTIME`" still read the same; `getRepeatableContainer()` is `Optional` and does
tell. The model therefore reports a `@Retention(RUNTIME)` on an annotation interface that wrote none; an
`Optional`-returning `getDeclaredRetentionPolicy()` would close that, if it ever matters.

javac: mirrors on the `TypeElement`, as `JavaAnnotationElement.isInherited()` does; the container is
`JavaAnnotationMetadataBuilder.getRepeatableContainerNameForType`. KSP: `declaration.annotations`, mapping
`AnnotationTarget` (`CLASS`→`TYPE`, `ANNOTATION_CLASS`→`ANNOTATION_TYPE`, `VALUE_PARAMETER`→`PARAMETER`,
`FUNCTION`/`PROPERTY_GETTER`/`PROPERTY_SETTER`→`METHOD`, `TYPE`→`TYPE_USE`, `TYPE_PARAMETER`, `FIELD`,
`CONSTRUCTOR`, `LOCAL_VARIABLE` as themselves; `PROPERTY`, `EXPRESSION`, `FILE`, `TYPEALIAS` have no `ElementType`);
container via `KotlinVisitorContext.getRepeatableContainerNameForType`. Groovy: `classNode.getAnnotations(Target)`
members, `GroovyAnnotationMetadataBuilder.getRepeatableContainerNameForType`. Unblocks, in every language: the
constructor return type (`AnnotatedTypes.verifyConstructor`, the `TYPE_USE` filter) and the container name where
the shape heuristic cannot apply; the retention accessor removes the `VisitorContext` round trip the processor
does per name.

### 41. Core divergence — an unannotated use of a type variable reports its declaration's annotations
`JavaElementAnnotationMetadataFactory.lookupTypeAnnotationsForGenericPlaceholder` reads the `TypeVariable`
mirror's annotations if there are any, else the `TypeParameterElement`'s. For `class C<@X T> { T field; }` the
field's type therefore carries `@X`, which the model forbids (`TypeVariable.annotations()` of a use are the use's;
`AnnotatedTypes.verifyTypeVariableField` relies on the use-site annotation being the only one). Changing
`getGenericTypeAnnotationMetadata()` itself would change nullability of `T` uses for everyone (`class Foo<@Nullable
T>`), so it must not change, and with the source view of #39 rejected the divergence stands: a use of a type
variable may report its declaration's annotations. The kit accepts either answer on the one bound it checks
(`verifyTypeVariableField`). Recorded, not pursued.

### 42. Core divergence — `MethodElement.getReceiverType()` javadoc versus the implementations
The interface (`MethodElement.java:131-143`) says an instance method or inner-class constructor "has a receiver
type derived from the declaring type"; `JavaMethodElement.getReceiverType()` returns empty unless the source wrote
`this`, and KSP/Groovy never override the default (empty). `ElementMethodInfo.receiverType()` synthesises the
declaring type itself, which is the right place for it (and must use `isInner() && !isStatic()`, since `isInner()`
is `NestingKind.isNested()`). Aligning the implementations with the javadoc would be a ~15-line behaviour change
with no caller inside core (`git grep getReceiverType` finds only the two files); recommended as a separate,
optional PR — or fix the javadoc.

### 43. Small core fixes found on the way — PR #13165 (5.3.x, open)
(a) `MetadataAnnotationValueVisitor.visitType` (`JavaAnnotationMetadataBuilder.java:650`) records a class member
for a `DeclaredType` and a `PrimitiveType` only; `String[].class` leaves `resolvedValue` null and the member
vanishes from the `AnnotationValue`. ~10 lines; additive; the kit has no such member. (b)
`GroovyClassElement.getDeclaredGenericPlaceholders()` returns `getBoundGenericTypes()` cast, which for a
parameterized *use* yields the arguments rather than the declared variables; read `classNode.redirect()
.getGenericsTypes()` instead. ~20 lines; Groovy-only behaviour change for the better; unblocks
`AnnotatedTypes.verifyTypeParameters`/`AnnotatedSuperTypes` on Groovy. (c) `isAbstract()` for an enum that
declares abstract methods is false in all three implementations (and in the class file it is true); answerable by
query, so no change proposed.

### 44. What KSP (and Groovy) still cannot answer after the above
- **Package annotations** on KSP: no `KotlinPackageElement`; `ClassElement.getPackage()` falls back to
  `PackageElement.of(name)`. Kotlin has no `package-info`; a Java one on the classpath compiles to a synthetic
  `package-info` interface that `Resolver.getClassDeclarationByName` may or may not surface — unverified, so treat
  `PackageInfo.annotations()` as empty under KSP.
- **`isRecord()`** of a Java record seen from KSP: `KSClassDeclaration.classKind` has no record kind.
- **Primitives with type annotations** under KSP are class elements (#38).
- **`throwsTypes()`** under KSP come from `@Throws` only, never annotated; **`receiverType()`** annotations cannot
  exist (Kotlin's extension receiver is a different construct and must not be mapped onto it).
- **Groovy** type annotations on a throws clause and on array dimensions are unverified; `GroovyElementAnnotationMetadataFactory.getTypeAnnotationsOnly` shows the mechanism exists for a `ClassNode`.
- **Member value classes** from KSP (`readAnnotationValue`) for `byte`/`short`/`char` members need a check that
  they arrive boxed as `Byte`/`Short`/`Character`, which `ElementAnnotationMember.kind()` keys on.
None of these is on the kit's critical path except the package section (one assertion block) and #38.

### 45. Design options and recommendation
**(a) Extend the AST** (#37–#40, #43) and make the model AST-only. Everything the model asks is then answered in
the language the visitor runs in; `ExtensionSourceModel`, `MirrorTypes`, `MirrorAnnotationInfo`,
`MirrorAnnotationMember` and the javac half of `ExtensionAnnotations` are deleted (~1,000 lines), and the
reflective unwrap of an `@Internal` record goes with them. Cost: a core release, and #39 is a real piece of
design work in `AbstractAnnotationMetadataBuilder`.

**(b) One seam, per-language fallbacks in `cdi-processor`**: turn `ExtensionSourceModel` into a
`LanguageModelSource` SPI chosen by `VisitorContext.getLanguage()`, with a javac implementation (today's code), a
KSP one reading `KSAnnotated`/`KSType` through `KotlinNativeElement.element`, and a Groovy one reading
`AnnotatedNode`s. It needs no core change but binds the processor to three `@Internal` native-element shapes
(`JavaNativeElement`, `KotlinNativeElement`, `GroovyNativeElement`), adds optional compile dependencies on
`symbol-processing-api` and Groovy to `cdi-processor`, and re-implements in three places what #37–#40 add once.

**Status, 13 Sep 2026 (evening).** Core merged #13162 (#40) and #13164 (#38) into 5.3.x, and #13166 (the
`getDeclaredAnnotationValuesByName` fix) into 5.2.x; #13165 (#43) and #13179 (Groovy generated sources, the
project-side follow-up below) are open on 5.3.x; #13163 (the source view) is closed. This branch builds on
`5.3.0-SNAPSHOT` (`-PnoMavenLocal` keeps a stale local publication from shadowing it) and uses the accessors:
sixteen of eighteen sections pass, `EnumMembers` and the constructor and primitive checks of `AnnotatedTypes`
included; the two pending sections are the accepted deviations (#37 array dimensions, #39 mixed repeatable).
The by-name fallback stays until 5.2.x is forward-merged into 5.3.x.

**Done, 13 Sep 2026.** The processor no longer reads the compiler at all: `SourceModel` is the one seam and
`AstSourceModel` its only implementation; `MirrorTypes`, `MirrorAnnotationInfo`, `MirrorAnnotationMember`,
`ExtensionSourceModel` and the `javax.lang.model` unwrap in `CdiScopeVisitor` are gone. The specification's view
of annotations is derived from the record (#39) and narrowed by a `LanguageModelAnnotationFilter` service, which
the kit module registers. The kit runs section by section and carries its pending sections as skipped tests
naming what each waits on. With core 5.2.1: fifteen of eighteen sections pass — `AnnotatedSuperTypes`,
`AnnotatedThrowsTypes`, `AnnotatedReceiverTypes`, `AnnotationInstances`, `AnnotationMembers`, the plain-class and
interface member sections, `InheritedMethods`, `InheritedFields`, `InheritedAnnotations`,
`JavaLangObjectMethods`, `PrimitiveTypes`, `BridgeMethods`, `DefaultConstructors`, `Equality` and the package
annotation; `AnnotatedTypes.verifyTypeParameters` passes too. Pending: `AnnotatedTypes` at the constructor's
return-type annotation (#40), then the primitive field (#38), then the array dimensions (#37, accepted);
`EnumMembers` at the type annotations of a `boolean` parameter (#38); `RepeatableAnnotations` at the mixed case
(#39, accepted). A pending section that starts passing fails the kit test, so the list shrinks as core lands.
`test-suite-kotlin` compiles a Kotlin class through KSP with the extension on the processor path and reads it
back through the model — the first language-neutral proof — which also surfaced that the visitor generated its
holder classes in Java syntax whatever the language (now written in the syntax of the language compiled).
`test-suite-groovy` does the same through the Groovy compiler.

**(c) Staged** (what was done, minus the seam's javac half, which was removed outright): move everything the
AST already answers (the **AST** rows of #36) off javac now — kinds, abstract
enums, type parameters, super types, thrown types, receivers, class/parameterized/wildcard/type-variable uses and
their type-use annotations, `@Inherited`, defaults via `VisitorContext.getAnnotationDefaultValues`, repetitions
via `getDeclaredAnnotationValuesByName` — and keep one small, explicit javac seam for the four remaining
questions (array dimensions, primitive type annotations, annotations as written, targets and container of an
annotation interface), each retired by the core PR that answers it. Recommended: it removes most of the
duplication immediately, keeps the kit green on javac at every step, and turns the core asks into four reviewable
PRs instead of one.

### 46. Order of core PRs, size, and what each unblocks for non-javac compilations
| # | Change | Size | Behaviour change? | Kit sections it unblocks on KSP/Groovy |
|---|---|---|---|---|
| 1 | #40 `AnnotationElement.getTargets()/getRepeatableContainer()/getRetentionPolicy()` — **merged, #13162** | ~60 core + ~40 × 3 languages + tests | no (additive) | `AnnotatedTypes.verifyConstructor` — verified passing on the snapshot |
| 2 | ~~#39 source view~~ — rejected, #13163 closed; derived in the processor instead (see #39) | — | — | — |
| 3 | #38 annotated `PrimitiveElement` for javac and Groovy — **merged, #13164** | ~60 + tests | no; KSP left out | `AnnotatedTypes.verifyPrimitiveField`, `EnumMembers.verifyConstructors` — verified passing on the snapshot |
| 4 | #43b Groovy `getDeclaredGenericPlaceholders()` + #43a array class literals — **open, #13165** | ~20 + tests | Groovy only, corrective | `AnnotatedTypes.verifyTypeParameters`, `AnnotatedSuperTypes`, `AnnotatedThrowsTypes` on Groovy |
| 5 | #43a array class literals; #42 receiver default (optional) | ~10; ~15 | no; **yes** (contract) | none in the kit; `AnnotatedReceiverTypes` already passes via the processor's own default |

All of it is additive except where marked; collections in the new builder paths are sized with the
`CollectionUtils` helpers (`newLinkedHashMap(size)`, `newHashSet(size)`; there is no list helper, so lists get `new ArrayList<>(size)`), and PR 2 in particular must be checked
against `inject-kotlin` because KSP's configuration metadata relies on by-name `has*` semantics that any change
near `addDeclaredRepeatable` can disturb. On javac nothing in the kit is waiting on core: the processor can move to
option (c)'s first stage against 5.2.1 today.
