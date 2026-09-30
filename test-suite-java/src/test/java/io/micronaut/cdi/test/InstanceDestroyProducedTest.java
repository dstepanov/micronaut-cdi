package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Destroying an instance through a lookup destroys it as the bean it is an instance of. Two producers of one class
 * are two beans, each with a disposer of its own, and an object is disposed of by the disposer of the producer that
 * produced it - whichever of the beans the lookup happens to come across first.
 */
class InstanceDestroyProducedTest {

    static final List<String> DISPOSED = new ArrayList<>();

    public static class Item {
        final String name;

        Item(String name) {
            this.name = name;
        }
    }

    @Singleton
    public static class Items {
        @Produces
        @Singleton
        @Named("destroyedFirst")
        Item first() {
            return new Item("first");
        }

        @Produces
        @Singleton
        @Named("destroyedSecond")
        Item second() {
            return new Item("second");
        }

        void disposeFirst(@Disposes @Named("destroyedFirst") Item item) {
            DISPOSED.add("first disposer: " + item.name);
        }

        void disposeSecond(@Disposes @Named("destroyedSecond") Item item) {
            DISPOSED.add("second disposer: " + item.name);
        }
    }

    @Test
    void anInstanceIsDisposedOfByTheDisposerOfItsOwnProducer() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Item> items = context.getBean(BeanManager.class).createInstance()
                .select(Item.class, Any.Literal.INSTANCE);
            List<Item> produced = new ArrayList<>();
            items.forEach(produced::add);
            assertEquals(2, produced.size());

            for (Item item : produced) {
                DISPOSED.clear();
                items.destroy(item);
                assertEquals(List.of(item.name + " disposer: " + item.name), DISPOSED);
            }
        }
    }
}
