package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.internal.metadata.CdiTypeIndex;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The generic hierarchy of the class of an event that is a bean of the specification is what its definition
 * recorded of its bean types: the type index records the classes no definition describes, and a bean's own record
 * answers the rest.
 */
class BeanClassEventWithoutReflectionTest {

    interface Signal<S> {
    }

    record Order(int number) {
    }

    record Invoice(int number) {
    }

    @Dependent
    static class OrderSignal implements Signal<Order> {
    }

    @Dependent
    static class AnySignal<T> implements Signal<T> {
    }

    @ApplicationScoped
    static class Listener {
        final List<String> heard = new ArrayList<>();

        void orders(@Observes Signal<Order> signal) {
            heard.add("order " + signal.getClass().getSimpleName());
        }

        void invoices(@Observes Signal<Invoice> signal) {
            heard.add("invoice " + signal.getClass().getSimpleName());
        }

        List<String> heard() {
            return List.copyOf(heard);
        }
    }

    @Dependent
    static class Sender {
        @Inject
        Event<Object> anything;
        @Inject
        Event<Signal<Order>> orders;
    }

    @Test
    void anEventOfABeanClassIsMatchedByWhatItsDefinitionRecorded() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Sender sender = context.getBean(Sender.class);
            sender.anything.fire(new OrderSignal());
            sender.orders.fire(new AnySignal<>());
            assertEquals(List.of("order OrderSignal", "order AnySignal"), context.getBean(Listener.class).heard());
            // the bean classes are not in the index, which holds the classes no definition describes
            for (BeanDefinition<?> definition : context.getBeanDefinitions(Qualifiers.byStereotype(CdiTypeIndex.class))) {
                for (AnnotationValue<Annotation> entry : definition.getAnnotationMetadata()
                    .getAnnotation(CdiTypeIndex.class).getAnnotations("value")) {
                    String name = entry.stringValue("name").orElse("");
                    assertFalse(name.equals(OrderSignal.class.getName()) || name.equals(AnySignal.class.getName()),
                        name + " is a bean, and recorded on its definition");
                }
            }
        }
    }
}
