package io.micronaut.cdi.tck;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanType;
import io.micronaut.inject.ProxyBeanDefinition;

import java.util.Set;

/**
 * Starts a container over the kit's scenarios of some packages, the way the kit deploys a scenario: as a
 * deployment of its own. The scenarios of every package are compiled onto one classpath here, and taken together
 * they are no deployment - two packages name a bean alike, a broken scenario's consumer is in a package beside
 * valid ones - so a container over all of them would be refused as it validated.
 */
final class ScenarioContexts {

    private static final String KIT = "org.jboss.cdi.tck.";

    /**
     * The scenarios that are part of no deployment the shared compilation can make. Each consumes a bean the
     * build leaves out of it as a broken scenario - an unproxyable bean, a broken producer, an interceptor
     * injected as a bean, CDI Full's {@code InterceptionFactory} - and so belongs to a deployment the kit
     * compiles and expects rejected on its own; or, for the two consumers of {@code Dao}, is deployed by the kit
     * in an archive of its own, and is ambiguous beside the other {@code Dao} beans of its package.
     */
    private static final Set<String> OUTSIDE_A_DEPLOYMENT = Set.of(
        KIT + "tests.implementation.producer.field.lifecycle.NullSpiderConsumerForBrokenProducer",
        KIT + "tests.lookup.clientProxy.unproxyable.beanConstructor.InjectionPointBean",
        KIT + "tests.lookup.clientProxy.unproxyable.finalClass.FishFarm",
        KIT + "tests.lookup.clientProxy.unproxyable.finalMethod.CarpFarm",
        KIT + "tests.lookup.clientProxy.unproxyable.finalMethod.FishFarm",
        KIT + "tests.lookup.clientProxy.unproxyable.finalMethod.ExtendedFishFarm",
        KIT + "tests.lookup.clientProxy.unproxyable.finalMethod.PikeFarm",
        KIT + "tests.lookup.clientProxy.unproxyable.privateConstructor.InjectionPointBean",
        KIT + "tests.lookup.injection.parameterized.ConsumerActualType",
        KIT + "tests.lookup.injection.parameterized.ConsumerRaw",
        KIT + "tests.lookup.typesafe.resolution.interceptor.Foo",
        KIT + "tests.se.discovery.trimmed.Bar"
    );

    private ScenarioContexts() {
    }

    /**
     * Starts a container with the container's own beans and the scenarios of the given packages, but for those
     * that are part of no deployment the shared compilation can make.
     *
     * @param packages The packages, each exactly: a package below one is not included with it
     * @return The started context
     */
    static ApplicationContext run(String... packages) {
        Set<String> admitted = Set.of(packages);
        return ApplicationContext.builder()
            .beansPredicate(bean -> {
                String owner = ownerOf(bean);
                return !owner.startsWith(KIT)
                    || admitted.contains(packageOf(owner)) && !OUTSIDE_A_DEPLOYMENT.contains(outerClassOf(owner))
                    && !OUTSIDE_A_DEPLOYMENT.contains(outerClassOf(bean.getBeanType().getName()));
            })
            .build()
            .start();
    }

    /**
     * The class a bean belongs to: the class that declares the producer of a produced bean, its client proxy
     * included, and the class of any other.
     */
    static String ownerOf(BeanType<?> bean) {
        if (bean instanceof BeanDefinition<?> definition) {
            String producer = definition.getAnnotationMetadata()
                .stringValue("io.micronaut.cdi.internal.metadata.CdiProducer", "declaringType").orElse(null);
            if (producer != null) {
                return producer;
            }
            if (definition.getAnnotationMetadata().hasAnnotation("io.micronaut.cdi.internal.metadata.CdiTypeIndex")) {
                // what the processor recorded of the classes of a package: data of the compilation
                return "";
            }
        }
        Class<?> type = bean instanceof ProxyBeanDefinition<?> proxy ? proxy.getTargetType() : bean.getBeanType();
        return type.getName();
    }

    private static String outerClassOf(String className) {
        int inner = className.indexOf('$');
        return inner < 0 ? className : className.substring(0, inner);
    }

    static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }
}
