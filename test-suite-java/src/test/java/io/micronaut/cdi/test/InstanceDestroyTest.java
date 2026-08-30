package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class InstanceDestroyTest {

    static final List<String> DESTROYED = new ArrayList<>();

    @Dependent
    public static class Widget {

        private String id;

        @jakarta.annotation.PostConstruct
        void up() {
            this.id = toString();
        }

        @PreDestroy
        void down() {
            DESTROYED.add("widget");
        }
    }

    @ApplicationScoped
    public static class Holder {
        @Inject
        Instance<Widget> widgets;

        Instance<Widget> widgets() {
            return widgets;
        }
    }

    @Dependent
    public static class DependentClient {
        @Inject
        Instance<Widget> widgets;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aHandleOfADependentClientDestroysOnClose() throws Exception {
        DESTROYED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            jakarta.enterprise.inject.spi.BeanManager manager =
                context.getBean(jakarta.enterprise.inject.spi.BeanManager.class);
            jakarta.enterprise.inject.spi.Bean<DependentClient> bean =
                (jakarta.enterprise.inject.spi.Bean<DependentClient>)
                    manager.resolve(manager.getBeans(DependentClient.class));
            jakarta.enterprise.context.spi.CreationalContext<DependentClient> cc =
                manager.createCreationalContext(bean);
            DependentClient client = bean.create(cc);
            Instance.Handle<Widget> first = client.widgets.getHandle();
            String a = first.get().toString();
            try (Instance.Handle<Widget> second = client.widgets.getHandle()) {
                assertNotEquals(a, second.get().toString());
            }
            assertEquals(List.of("widget"), DESTROYED, "closing the handle should destroy the widget");
        }
    }

    @Test
    void destroyingAnInstanceMemberRunsItsPreDestroy() {
        DESTROYED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Holder holder = context.getBean(Holder.class);
            Widget widget = holder.widgets().get();
            holder.widgets().destroy(widget);
            assertEquals(List.of("widget"), DESTROYED, "destroy(instance) should run @PreDestroy");
        }
    }

    @Test
    void closingAHandleDestroysItsDependent() {
        DESTROYED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Holder holder = context.getBean(Holder.class);
            Instance.Handle<Widget> first = holder.widgets().getHandle();
            String a = first.get().toString();
            try (Instance.Handle<Widget> second = holder.widgets().getHandle()) {
                assertNotEquals(a, second.get().toString());
            }
            assertEquals(List.of("widget"), DESTROYED, "closing the handle should destroy the widget");
        }
    }
}
