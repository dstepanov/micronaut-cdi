package io.micronaut.cdi.tck;

import io.micronaut.context.ApplicationContext;
import org.jboss.cdi.tck.tests.implementation.disposal.method.definition.Calisoga;
import org.jboss.cdi.tck.tests.implementation.disposal.method.definition.Deadliest;
import org.jboss.cdi.tck.tests.implementation.disposal.method.definition.SandSpider;
import org.jboss.cdi.tck.tests.implementation.disposal.method.definition.Scary;
import org.jboss.cdi.tck.tests.implementation.disposal.method.definition.SpiderProducer;
import org.jboss.cdi.tck.tests.implementation.disposal.method.definition.Tame;
import org.jboss.cdi.tck.tests.implementation.disposal.method.definition.Tarantula;
import org.jboss.cdi.tck.tests.implementation.disposal.method.definition.WebSpider;
import org.jboss.cdi.tck.tests.implementation.disposal.method.definition.Widow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code org.jboss.cdi.tck.tests.implementation.disposal.method.definition.DisposalMethodDefinitionTest}, run
 * against the kit's own scenarios.
 *
 * <p>Each of these creates a bean and destroys it, and asserts on what the disposer beside its producer
 * recorded. A container is started per test, because what is asserted is a side effect the scenarios keep in
 * static state.</p>
 */
class DisposalMethodDefinitionTckTest {

    private ApplicationContext context;

    @BeforeEach
    void start() {
        SpiderProducer.reset();
        context = ApplicationContext.run();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    /**
     * The kit's {@code newDependentInstance(...)} followed by {@code destroy()}: a dependent bean is looked up,
     * used and then destroyed by the lookup that resolved it.
     */
    private <T> void createAndDestroy(Class<T> type, java.lang.annotation.Annotation qualifier) {
        try (jakarta.enterprise.inject.Instance.Handle<T> handle =
                 jakarta.enterprise.inject.spi.CDI.current().select(type, qualifier).getHandle()) {
            assertNotNull(handle.get());
        }
    }

    /**
     * {@code testBindingTypesAppliedToDisposalMethodParameters}: the disposer that disposes of a produced bean
     * is the one whose disposed parameter is qualified the way the producer is.
     */
    @Test
    void theDisposerIsTheOneQualifiedTheWayTheProducerIs() {
        assertFalse(SpiderProducer.isDeadliestTarantulaDestroyed());
        createAndDestroy(Tarantula.class, new Deadliest.Literal());
        assertTrue(SpiderProducer.isDeadliestTarantulaDestroyed(),
            "the disposer of the deadliest tarantula should have run");
    }

    /**
     * {@code testDisposalMethodParametersGetInjected}: a disposer may take parameters besides the one it
     * disposes of, and they are injection points resolved from the container.
     */
    @Test
    void aDisposerMayTakeInjectedParametersBesideTheOneItDisposesOf() {
        createAndDestroy(SandSpider.class, new Deadliest.Literal());
        assertTrue(SpiderProducer.isDeadliestSandSpiderDestroyed());
    }

    /**
     * {@code testDisposalMethodOnNonBean}: a disposer declared by a class that is not a bean is not a disposer,
     * and is not called.
     */
    @Test
    void aDisposerOnAClassThatIsNotABeanIsNotCalled() {
        createAndDestroy(WebSpider.class, new Deadliest.Literal());
        assertFalse(org.jboss.cdi.tck.tests.implementation.disposal.method.definition.DisposalNonBean
            .isWebSpiderdestroyed());
    }

    /**
     * {@code testDisposalMethodForMultipleProducerMethods}: a disposer whose disposed parameter is qualified
     * {@code Any} disposes of what every producer of the type produced.
     */
    @Test
    void aDisposerQualifiedAnyDisposesOfEveryProducerOfTheType() {
        createAndDestroy(Widow.class, new Deadliest.Literal());
        createAndDestroy(Widow.class, new Tame.Literal());
        org.junit.jupiter.api.Assertions.assertEquals(2, SpiderProducer.getWidowsDestroyed(),
            "both widows should have been disposed of");
    }

    /**
     * {@code testDisposalMethodCalledForProducerField}: a producer field has a disposer as a producer method
     * does, matched to it the same way.
     */
    @Test
    void aProducerFieldHasADisposerToo() {
        createAndDestroy(Calisoga.class, new Scary.Literal());
        assertTrue(SpiderProducer.isScaryBlackWidowDestroyed(),
            "the disposer of the scary calisoga should have run");
        assertFalse(SpiderProducer.isTameBlackWidowDestroyed(),
            "the disposer of the tame calisoga should not have run");
    }
}
