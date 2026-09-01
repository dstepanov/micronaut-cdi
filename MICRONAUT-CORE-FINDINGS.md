# Micronaut Core findings from building CDI Lite

What implementing the CDI Lite TCK on top of Micronaut surfaced about Micronaut itself: bugs found, fixes made in
the local checkout (`../micronaut-core`, published to mavenLocal), and API gaps worth taking upstream. Each entry
names where the behaviour lives and what this project does about it meanwhile.

## Bugs

### 1. A `@Primary` bean with another qualifier NPEs when resolved by its produced bean — FIXED locally
`BeanDefinitionWriter.getQualifier` maps `@Primary` to a literal null qualifier; composed with another qualifier
that null lands in the `Qualifiers.byQualifiers(...)` array and `CompositeQualifier.filterQualified` dereferences
it. Fixed in the local checkout by leaving `@Primary` out of composite qualifiers
(`core-processor/.../writer/BeanDefinitionWriter.java`); regression spec added at
`inject-java/src/test/groovy/io/micronaut/inject/cdiscenarios/PrimaryQualifiedFactorySpec.groovy`.

### 2. `AbstractConcurrentCustomScope.remove(BeanIdentifier)` closes the bean but never removes it — FIXED locally
`inject/.../context/scope/AbstractConcurrentCustomScope.java`: `remove` does `scopeMap.get(identifier)` +
`close()`, so the destroyed instance stays in the map and keeps being served — a client proxy resolves the
*destroyed* instance forever after. Found via the TCK's `AlterableContextTest`. Fixed locally with
`scopeMap.remove(identifier)`; this project also removes-then-closes in its own scopes
(`cdi/.../context/ApplicationScope.java`, `RequestScope.java`) so it works against the published snapshot too.

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

### 7. `getAnnotationNamesByStereotype` returns the *declared* annotation for transitive chains
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

### 11. `@InjectScope` on a constructor/factory parameter never destroys — two compounding bugs — FIXED locally
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
