package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.internal.runtime.ObserverRegistry;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The synthetic components of a build compatible extension, with neither the processor that runs an extension
 * nor a reflection module on the classpath: the phases ran while this class compiled - the compilation would
 * have failed had the registration phase not been told about the synthetic bean - and the container reads what
 * they recorded.
 */
class SyntheticComponentsWithoutReflectionTest {

    @ApplicationScoped
    static class Host {

        @Inject
        Greeting greeting;

        String greet() {
            return greeting.text();
        }
    }

    private ApplicationContext context;

    @BeforeEach
    void start() {
        context = ApplicationContext.run();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @Test
    void thereIsNoReflectionModule() {
        assertFalse(context.findBean(CdiReflection.class).isPresent());
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.micronaut.cdi.reflection.ReflectiveCdi"));
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.micronaut.reflection.ReflectionAnnotations"));
    }

    @Test
    void theContainerRunsNoExtension() {
        // what runs an extension is the processor, which is not here; and the extension, which happens to be
        // on this classpath beside the classes it names, was not instantiated by the container that started
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor"));
        assertEquals(0, GreetingExtension.INSTANTIATED.get());
    }

    @Test
    void aSyntheticBeanIsCreatedByItsCreatorFromTheRecordedParameters() {
        Greeting greeting = context.getBean(Greeting.class);
        assertEquals("hello", greeting.text());
        assertEquals(11, greeting.volume());
        assertEquals(Greeting.Tone.WARM, greeting.tone());
    }

    @Test
    void aSyntheticBeanIsInjected() {
        assertEquals("hello", context.getBean(Host.class).greet());
    }

    @Test
    void aSyntheticBeanIsABeanOfTheContainer() {
        CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
        assertEquals(1, container.getBeans("greeting").size());
        Bean<?> bean = container.getBeans("greeting").iterator().next();
        assertEquals(ApplicationScoped.class, bean.getScope());
        assertEquals("hello", container.createInstance().select(Greeting.class).get().text());
        // the creator and the disposer are instantiated through definitions, which are not beans of the application
        assertTrue(container.getBeans(GreetingCreator.class).isEmpty());
        assertTrue(container.getBeans(GreetingDisposer.class).isEmpty());
    }

    @Test
    void aSyntheticBeanIsDisposedOfByItsDisposer() {
        CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
        assertNotNull(container.createInstance().select(Greeting.class).get().text());
        int before = GreetingDisposer.DISPOSED.size();
        Bean<?> bean = container.getBeans("greeting").iterator().next();
        ((AlterableContext) container.getContext(ApplicationScoped.class)).destroy(bean);
        assertEquals(before + 1, GreetingDisposer.DISPOSED.size());
        assertEquals("hello / hello", GreetingDisposer.DISPOSED.get(before));
    }

    @Test
    void aSyntheticObserverIsNotified() {
        int before = PingObserver.OBSERVED.size();
        context.getBean(CdiBeanContainer.class).getEvent().select(Ping.class).fire(new Ping("ping"));
        assertEquals(before + 1, PingObserver.OBSERVED.size());
        assertEquals("ping heard by the synthetic observer", PingObserver.OBSERVED.get(before));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aQualifiedSyntheticObserverIsNotifiedDirectlyWithoutAnInstanceOfItsQualifier() {
        // the qualifier has no generated builder, and there is no reflection module to make an instance of it: the
        // metadata of a direct notification makes one only if the observer asks for it
        ObserverMethod<Whisper> observer = (ObserverMethod<Whisper>) context.getBean(ObserverRegistry.class)
            .observers().stream()
            .filter(candidate -> candidate.getObservedType() == Whisper.class)
            .findFirst().orElseThrow();
        int before = WhisperObserver.OBSERVED.size();
        observer.notify(new Whisper("psst"));
        assertEquals(before + 1, WhisperObserver.OBSERVED.size());
        assertEquals("psst", WhisperObserver.OBSERVED.get(before));
    }
}
