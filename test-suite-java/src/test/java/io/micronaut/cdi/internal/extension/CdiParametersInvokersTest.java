package io.micronaut.cdi.internal.extension;

import io.micronaut.cdi.internal.metadata.CdiRecordedInvoker;
import io.micronaut.cdi.internal.metadata.CdiSyntheticParameter;
import io.micronaut.cdi.internal.runtime.RecordedInvoker;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import jakarta.enterprise.invoke.Invoker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The invokers an extension hands a synthetic component as parameters are read once, as the parameters are: the
 * container validates the same invokers it later hands out, every retrieval hands out those, an array handed out is
 * the caller's to change, and reading them resolves nothing of the beans they invoke.
 */
class CdiParametersInvokersTest {

    @Test
    void everyRetrievalHandsOutTheInvokersTheContainerValidates() {
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            CdiParameters parameters = new CdiParameters(context, List.of(
                invokers("one", false, record("first")),
                invokers("all", true, record("second"), record("third"))));

            List<RecordedInvoker> validated = parameters.invokers();
            assertEquals(3, validated.size());
            Invoker<?, ?> one = parameters.get("one", Invoker.class);
            assertSame(validated.get(0), one);
            assertSame(one, parameters.get("one", Invoker.class));
            Invoker<?, ?>[] all = parameters.get("all", Invoker[].class);
            assertSame(validated.get(1), all[0]);
            assertSame(validated.get(2), all[1]);
            assertEquals(validated, parameters.invokers(), "read again, the same invokers");
        }
    }

    @Test
    void anArrayHandedOutIsTheCallersToChange() {
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            CdiParameters parameters = new CdiParameters(context, List.of(
                invokers("all", true, record("first"), record("second"))));
            Invoker<?, ?>[] handedOut = parameters.get("all", Invoker[].class);
            Invoker<?, ?> first = handedOut[0];
            handedOut[0] = null;
            handedOut[1] = first;

            Invoker<?, ?>[] again = parameters.get("all", Invoker[].class);
            assertNotSame(handedOut, again);
            assertSame(first, again[0]);
            assertSame(parameters.invokers().get(1), again[1]);
        }
    }

    @Test
    void aParameterRecordedTwiceIsTheLastOneRecorded() {
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            CdiParameters parameters = new CdiParameters(context, List.of(
                invokers("twice", true, record("first"), record("second")),
                invokers("twice", true, record("third"))));
            assertEquals(1, parameters.get("twice", Invoker[].class).length);
            assertEquals(1, parameters.invokers().size());
        }
    }

    @Test
    void readingTheInvokersResolvesNothingOfTheBeansTheyInvoke() {
        // a context that never started has no bean to resolve, and a class no loader has is no bean at all: the
        // target of an invoker is found the first time it is invoked
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            CdiParameters parameters = new CdiParameters(context, List.of(
                invokers("one", false, record("absent"))));
            assertEquals(1, parameters.invokers().size());
            assertSame(parameters.invokers().get(0), parameters.get("one", Invoker.class));
            assertEquals(false, context.isRunning());
        }
    }

    @SafeVarargs
    private static AnnotationValue<CdiSyntheticParameter> invokers(String name, boolean array,
                                                                   AnnotationValue<CdiRecordedInvoker>... records) {
        return AnnotationValue.builder(CdiSyntheticParameter.class)
            .member("name", name)
            .member("kind", "INVOKER")
            .member("array", array)
            .member(CdiSyntheticParameter.INVOKERS, records)
            .build();
    }

    private static AnnotationValue<CdiRecordedInvoker> record(String method) {
        return AnnotationValue.builder(CdiRecordedInvoker.class)
            .member("beanClass", "org.example.absent.Target")
            .member("method", method)
            .build();
    }
}
