package org.example.cdi.extension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.AfterTypeDiscovery;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessBean;
import jakarta.enterprise.inject.spi.ProcessBeanAttributes;
import jakarta.enterprise.inject.spi.ProcessInjectionTarget;
import jakarta.enterprise.inject.spi.ProcessManagedBean;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The subset of the portable extension lifecycle (CDI Full, section 3.9) the SE bootstrap runs over compiled
 * beans, with micronaut-cdi-reflection: what is fired and in which order, what an extension can add, and that
 * what would change a compiled bean is refused rather than ignored.
 *
 * <p>The classes are outside {@code io.micronaut}, as an application's are.</p>
 */
class PortableExtensionTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Tagged {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Marked {
    }

    static final AnnotationLiteral<Tagged> TAGGED = new AnnotationLiteral<>() {
    };

    static final AnnotationLiteral<Marked> MARKED = new AnnotationLiteral<>() {
    };

    @Dependent
    static class Widget {
    }

    @Dependent
    static class SpecialWidget extends Widget {
    }

    @Dependent
    static class Workshop {
        @Produces
        @Tagged
        Integer count = 3;
    }

    @Dependent
    @Alternative
    @Priority(1)
    static class SpareWidget extends Widget {
    }

    @RequestScoped
    static class Visit {
        String name() {
            return "visit";
        }
    }

    @ApplicationScoped
    static class WantsATaggedWidget {
        @Inject
        @Tagged
        Widget widget;

        Widget widget() {
            return widget;
        }
    }

    record Signal(String text) {
    }

    private static SeContainerInitializer bootstrap(Class<?>... beanClasses) {
        return SeContainerInitializer.newInstance().disableDiscovery().addBeanClasses(beanClasses);
    }

    // -- the events, and their order

    static class Recording implements Extension {
        final List<String> seen = new ArrayList<>();

        void before(@Observes BeforeBeanDiscovery event, BeanManager manager) {
            seen.add("BeforeBeanDiscovery");
        }

        void type(@Observes ProcessAnnotatedType<Widget> event) {
            seen.add("ProcessAnnotatedType " + event.getAnnotatedType().getJavaClass().getSimpleName());
        }

        void types(@Observes AfterTypeDiscovery event) {
            seen.add("AfterTypeDiscovery " + event.getAlternatives().stream().map(Class::getSimpleName).toList());
        }

        void target(@Observes ProcessInjectionTarget<Widget> event) {
            seen.add("ProcessInjectionTarget " + event.getAnnotatedType().getJavaClass().getSimpleName());
        }

        void attributes(@Observes ProcessBeanAttributes<Widget> event) {
            seen.add("ProcessBeanAttributes " + event.getBeanAttributes().getTypes().contains(Widget.class));
        }

        void bean(@Observes ProcessBean<Widget> event) {
            seen.add("ProcessBean " + event.getBean().getBeanClass().getSimpleName());
        }

        void managed(@Observes ProcessManagedBean<Widget> event) {
            seen.add("ProcessManagedBean " + event.getAnnotatedBeanClass().getJavaClass().getSimpleName());
        }

        void produced(@Observes ProcessBeanAttributes<Integer> event) {
            seen.add("ProcessBeanAttributes of the produced " + event.getBeanAttributes().getTypes().contains(Integer.class));
        }

        void beans(@Observes AfterBeanDiscovery event) {
            seen.add("AfterBeanDiscovery");
        }

        void validated(@Observes AfterDeploymentValidation event, BeanManager manager) {
            seen.add("AfterDeploymentValidation " + !manager.getBeans(Widget.class).isEmpty());
        }
    }

    @Test
    void theEventsOfTheLifecycleAreFiredInOrderOverWhatWasCompiled() {
        Recording recording = new Recording();
        try (SeContainer container = bootstrap(Widget.class, SpecialWidget.class, SpareWidget.class, Workshop.class)
            .addExtensions(recording).initialize()) {
            List<String> seen = recording.seen;
            // every event, once
            assertEquals(Set.of("BeforeBeanDiscovery", "ProcessAnnotatedType Widget",
                "AfterTypeDiscovery [SpareWidget]", "ProcessInjectionTarget Widget", "ProcessBeanAttributes true",
                "ProcessBean Widget", "ProcessManagedBean Widget", "ProcessBeanAttributes of the produced true",
                "AfterBeanDiscovery", "AfterDeploymentValidation true"), Set.copyOf(seen));
            assertEquals(10, seen.size(), seen.toString());
            // the phases in order: before discovery, the types, after the types, the beans, after the beans,
            // after validation
            assertEquals(List.of("BeforeBeanDiscovery", "ProcessAnnotatedType Widget",
                "AfterTypeDiscovery [SpareWidget]"), seen.subList(0, 3));
            assertEquals(List.of("AfterBeanDiscovery", "AfterDeploymentValidation true"), seen.subList(8, 10));
            // and the events of one bean in the order bean discovery has them
            assertTrue(seen.indexOf("ProcessInjectionTarget Widget") < seen.indexOf("ProcessBeanAttributes true"));
            assertTrue(seen.indexOf("ProcessBeanAttributes true") < seen.indexOf("ProcessBean Widget"));
            assertTrue(seen.indexOf("ProcessBeanAttributes true") < seen.indexOf("ProcessManagedBean Widget"));
        }
    }

    static class Later implements Extension {
        final List<String> order;

        Later(List<String> order) {
            this.order = order;
        }

        void first(@Observes @Priority(10) BeforeBeanDiscovery event) {
            order.add("first");
        }

        void last(@Observes @Priority(5000) BeforeBeanDiscovery event) {
            order.add("last");
        }

        void middle(@Observes BeforeBeanDiscovery event) {
            order.add("middle");
        }
    }

    @Test
    void theObserversOfAnEventAreNotifiedInTheOrderOfTheirPriority() {
        List<String> order = new ArrayList<>();
        try (SeContainer container = bootstrap(Widget.class).addExtensions(new Later(order)).initialize()) {
            assertEquals(List.of("first", "middle", "last"), order);
        }
    }

    // -- an extension is a bean

    public static class Counting implements Extension {
        int notified;

        void before(@Observes BeforeBeanDiscovery event) {
            notified++;
        }
    }

    @Test
    void anExtensionHandedOverIsTheBeanOfItsClass() {
        Counting extension = new Counting();
        try (SeContainer container = bootstrap(Widget.class).addExtensions(extension).initialize()) {
            assertSame(extension, container.select(Counting.class).get());
            assertEquals(1, extension.notified);
        }
    }

    @Test
    void anExtensionNamedByItsClassIsCreatedAndIsTheBeanOfItsClass() {
        try (SeContainer container = bootstrap(Widget.class).addExtensions(Counting.class).initialize()) {
            assertEquals(1, container.select(Counting.class).get().notified);
        }
    }

    @Test
    void anExtensionOfTheServiceLoaderTakesPart() {
        ServiceLoadedExtension.NOTIFIED.set(0);
        try (SeContainer container = bootstrap(Widget.class).initialize()) {
            assertEquals(1, ServiceLoadedExtension.NOTIFIED.get());
            assertTrue(container.select(ServiceLoadedExtension.class).isResolvable());
        }
    }

    // -- a qualifier added to a bean class

    static class Tagging implements Extension {
        void tag(@Observes ProcessAnnotatedType<Widget> event) {
            event.configureAnnotatedType().add(TAGGED);
        }
    }

    @Test
    void aQualifierAddedToABeanClassQualifiesTheBeanForALookup() {
        try (SeContainer container = bootstrap(Widget.class, SpecialWidget.class).addExtensions(new Tagging())
            .initialize()) {
            Instance<Widget> tagged = container.select(Widget.class, TAGGED);
            assertTrue(tagged.isResolvable(), "the bean of the class the qualifier was added to, and no other");
            assertEquals(Widget.class, tagged.get().getClass());
            Bean<?> bean = container.getBeanManager().resolve(container.getBeanManager().getBeans(Widget.class, TAGGED));
            assertTrue(bean.getQualifiers().stream().anyMatch(each -> each.annotationType() == Tagged.class),
                () -> bean.getQualifiers().toString());
            assertFalse(bean.getQualifiers().contains(Default.Literal.INSTANCE),
                "a bean with a qualifier of its own does not have the default one");
            assertEquals(SpecialWidget.class, container.select(Widget.class).get().getClass(),
                "and is no longer what an unqualified lookup resolves");
        }
    }

    @Test
    void aQualifierAddedToABeanClassIsNotSeenByAnInjectionPoint() {
        // the limit of the overlay: an injection point is resolved by Micronaut from the compiled metadata of
        // the definitions, which a qualifier added as the container starts is not part of
        try (SeContainer container = bootstrap(Widget.class, WantsATaggedWidget.class).addExtensions(new Tagging())
            .initialize()) {
            assertThrows(RuntimeException.class, () -> container.select(WantsATaggedWidget.class).get().widget());
        }
    }

    static class Qualifying implements Extension {
        void qualifier(@Observes BeforeBeanDiscovery event) {
            event.addQualifier(Marked.class);
        }
    }

    @Test
    void anAnnotationAnExtensionMakesAQualifierIsOne() {
        try (SeContainer container = bootstrap(Widget.class).addExtensions(new Qualifying()).initialize()) {
            assertTrue(container.getBeanManager().isQualifier(Marked.class));
        }
        try (SeContainer container = bootstrap(Widget.class).initialize()) {
            assertFalse(container.getBeanManager().isQualifier(Marked.class), "and only in that container");
        }
    }

    // -- a context and an observer method added

    static class AlwaysActiveRequestContext implements AlterableContext {
        final Map<Contextual<?>, Object> instances = new ConcurrentHashMap<>();

        @Override
        public Class<? extends Annotation> getScope() {
            return RequestScoped.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            return (T) instances.computeIfAbsent(contextual, key -> contextual.create(creationalContext));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual) {
            return (T) instances.get(contextual);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void destroy(Contextual<?> contextual) {
            instances.remove(contextual);
        }
    }

    static class Adding implements Extension {
        final AlwaysActiveRequestContext context = new AlwaysActiveRequestContext();
        final List<String> heard = new ArrayList<>();

        void add(@Observes AfterBeanDiscovery event) {
            event.addContext(context);
            event.addObserverMethod(new ObserverMethod<Signal>() {
                @Override
                public Class<?> getBeanClass() {
                    return Adding.class;
                }

                @Override
                public Type getObservedType() {
                    return Signal.class;
                }

                @Override
                public Set<Annotation> getObservedQualifiers() {
                    return Set.of();
                }

                @Override
                public Reception getReception() {
                    return Reception.ALWAYS;
                }

                @Override
                public TransactionPhase getTransactionPhase() {
                    return TransactionPhase.IN_PROGRESS;
                }

                @Override
                public void notify(Signal event) {
                    heard.add(event.text());
                }
            });
        }
    }

    @Test
    void aContextAnExtensionAddsForABuiltInScopeTakesThePlaceOfTheContainersOwn() {
        Adding adding = new Adding();
        try (SeContainer container = bootstrap(Visit.class).addExtensions(adding).initialize()) {
            // no request is active: the context the extension added always is
            assertEquals("visit", container.select(Visit.class).get().name());
            assertEquals(1, adding.context.instances.size());
        }
    }

    @Test
    void anObserverMethodAnExtensionAddsIsNotified() {
        Adding adding = new Adding();
        try (SeContainer container = bootstrap(Widget.class).addExtensions(adding).initialize()) {
            container.getBeanManager().getEvent().select(Signal.class).fire(new Signal("heard"));
            assertEquals(List.of("heard"), adding.heard);
        }
    }

    // -- problems

    static class Complaining implements Extension {
        final String when;

        Complaining(String when) {
            this.when = when;
        }

        void bean(@Observes ProcessBean<Widget> event) {
            if (when.equals("bean")) {
                event.addDefinitionError(new IllegalStateException("a bad bean"));
            }
        }

        void beans(@Observes AfterBeanDiscovery event) {
            if (when.equals("discovery")) {
                event.addDefinitionError(new IllegalStateException("a bad definition"));
            }
            if (when.equals("thrown")) {
                throw new IllegalStateException("thrown by an observer");
            }
        }

        void validated(@Observes AfterDeploymentValidation event) {
            if (when.equals("validation")) {
                event.addDeploymentProblem(new IllegalStateException("a bad deployment"));
            }
        }
    }

    @Test
    void aDefinitionErrorAnExtensionRegistersFailsTheBootstrap() {
        for (String when : List.of("bean", "discovery", "thrown")) {
            DefinitionException failure = assertThrows(DefinitionException.class,
                () -> bootstrap(Widget.class).addExtensions(new Complaining(when)).initialize(), when);
            assertTrue(failure.getCause() instanceof IllegalStateException, when);
        }
    }

    @Test
    void aDeploymentProblemAnExtensionRegistersFailsTheBootstrap() {
        DeploymentException failure = assertThrows(DeploymentException.class,
            () -> bootstrap(Widget.class).addExtensions(new Complaining("validation")).initialize());
        assertEquals("a bad deployment", failure.getCause().getMessage());
    }

    // -- what would change a compiled bean is refused

    static class Asking implements Extension {
        final String operation;

        Asking(String operation) {
            this.operation = operation;
        }

        private void when(String name, Runnable action) {
            if (operation.equals(name)) {
                action.run();
            }
        }

        void before(@Observes BeforeBeanDiscovery event) {
            when("BeforeBeanDiscovery.addScope", () -> event.addScope(Marked.class, true, false));
            when("BeforeBeanDiscovery.addStereotype", () -> event.addStereotype(Marked.class));
            when("BeforeBeanDiscovery.addInterceptorBinding", () -> event.addInterceptorBinding(Marked.class));
            when("BeforeBeanDiscovery.addAnnotatedType", () -> event.addAnnotatedType(Widget.class, "id"));
            when("BeforeBeanDiscovery.configureQualifier", () -> event.configureQualifier(Marked.class));
            when("BeforeBeanDiscovery.configureInterceptorBinding",
                () -> event.configureInterceptorBinding(Marked.class));
        }

        void type(@Observes ProcessAnnotatedType<Widget> event) {
            when("ProcessAnnotatedType.veto", event::veto);
            when("ProcessAnnotatedType.setAnnotatedType", () -> event.setAnnotatedType(event.getAnnotatedType()));
            when("AnnotatedTypeConfigurator.add", () -> event.configureAnnotatedType().add(MARKED));
            when("AnnotatedTypeConfigurator.remove", () -> event.configureAnnotatedType().remove(each -> true));
            when("AnnotatedTypeConfigurator.methods", () -> event.configureAnnotatedType().methods());
            when("AnnotatedTypeConfigurator.fields", () -> event.configureAnnotatedType().fields());
            when("AnnotatedTypeConfigurator.constructors", () -> event.configureAnnotatedType().constructors());
        }

        void types(@Observes AfterTypeDiscovery event) {
            when("AfterTypeDiscovery.getInterceptors", event::getInterceptors);
            when("AfterTypeDiscovery.getDecorators", event::getDecorators);
            when("AfterTypeDiscovery.addAnnotatedType", () -> event.addAnnotatedType(Widget.class, "id"));
        }

        void target(@Observes ProcessInjectionTarget<Widget> event) {
            when("ProcessInjectionTarget.getInjectionTarget", event::getInjectionTarget);
            when("ProcessInjectionTarget.setInjectionTarget", () -> event.setInjectionTarget(null));
        }

        void attributes(@Observes ProcessBeanAttributes<Widget> event) {
            when("ProcessBeanAttributes.veto", event::veto);
            when("ProcessBeanAttributes.setBeanAttributes",
                () -> event.setBeanAttributes(event.getBeanAttributes()));
            when("ProcessBeanAttributes.configureBeanAttributes", event::configureBeanAttributes);
            when("ProcessBeanAttributes.ignoreFinalMethods", event::ignoreFinalMethods);
        }

        void managed(@Observes ProcessManagedBean<Widget> event) {
            when("ProcessManagedBean.createInvoker", () -> event.createInvoker(null));
        }

        void beans(@Observes AfterBeanDiscovery event) {
            when("AfterBeanDiscovery.addBean", event::addBean);
            when("AfterBeanDiscovery.addObserverMethod()", event::addObserverMethod);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "BeforeBeanDiscovery.addScope", "BeforeBeanDiscovery.addStereotype",
        "BeforeBeanDiscovery.addInterceptorBinding", "BeforeBeanDiscovery.addAnnotatedType",
        "BeforeBeanDiscovery.configureQualifier", "BeforeBeanDiscovery.configureInterceptorBinding",
        "ProcessAnnotatedType.veto", "ProcessAnnotatedType.setAnnotatedType", "AnnotatedTypeConfigurator.add",
        "AnnotatedTypeConfigurator.remove", "AnnotatedTypeConfigurator.methods", "AnnotatedTypeConfigurator.fields",
        "AnnotatedTypeConfigurator.constructors", "AfterTypeDiscovery.getInterceptors",
        "AfterTypeDiscovery.getDecorators", "AfterTypeDiscovery.addAnnotatedType",
        "ProcessInjectionTarget.getInjectionTarget", "ProcessInjectionTarget.setInjectionTarget",
        "ProcessBeanAttributes.veto", "ProcessBeanAttributes.setBeanAttributes",
        "ProcessBeanAttributes.configureBeanAttributes", "ProcessBeanAttributes.ignoreFinalMethods",
        "ProcessManagedBean.createInvoker", "AfterBeanDiscovery.addBean", "AfterBeanDiscovery.addObserverMethod()"})
    void whatWouldChangeACompiledBeanIsRefused(String operation) {
        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
            () -> bootstrap(Widget.class).addExtensions(new Asking(operation)).initialize());
        assertTrue(refused.getMessage().contains("compile-time container"), refused.getMessage());
        assertTrue(refused.getMessage().startsWith(operation), refused.getMessage());
    }

    static class ObservingTheApplication implements Extension {
        void signal(@Observes Signal signal) {
        }
    }

    @Test
    void anExtensionObservingAnEventOfTheApplicationIsRefused() {
        assertThrows(UnsupportedOperationException.class,
            () -> bootstrap(Widget.class).addExtensions(new ObservingTheApplication()).initialize());
    }

    static class Hoarding implements Extension {
        BeforeBeanDiscovery kept;

        void before(@Observes BeforeBeanDiscovery event) {
            kept = event;
        }
    }

    @Test
    void anEventIsOnlyToBeUsedWhileItsObserversAreNotified() {
        Hoarding hoarding = new Hoarding();
        try (SeContainer container = bootstrap(Widget.class).addExtensions(hoarding).initialize()) {
            assertThrows(IllegalStateException.class, () -> hoarding.kept.addQualifier(Marked.class));
        }
    }
}
