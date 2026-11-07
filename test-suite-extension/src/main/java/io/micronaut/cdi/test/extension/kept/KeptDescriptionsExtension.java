package io.micronaut.cdi.test.extension.kept;

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Keeps the bean and the observer of a class marked {@link KeptDescription} from the registration phase, and reads
 * them in the synthesis and validation phases, which the specification has run once every bean is registered: in a
 * compilation of several rounds, after the round that described them. What synthesis read is the
 * {@link KeptDescriptionReport}; validation fails the compilation unless it reads the same.
 */
public final class KeptDescriptionsExtension implements BuildCompatibleExtension {

    private final List<BeanInfo> beans = new ArrayList<>();
    private final List<ObserverInfo> observers = new ArrayList<>();
    private final List<ClassInfo> enhanced = new ArrayList<>();
    private String synthesised = "";

    /**
     * Qualifies a marked class, and keeps it as it was enhanced.
     *
     * @param config The class
     */
    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = KeptDescription.class)
    public void qualify(ClassConfig config) {
        config.addAnnotation(KeptQualifier.Literal.INSTANCE);
        enhanced.add(config.info());
    }

    /**
     * Keeps the bean of a marked class.
     *
     * @param bean The bean
     */
    @Registration(types = Object.class)
    public void keepBean(BeanInfo bean) {
        if (bean.isClassBean() && bean.declaringClass().hasAnnotation(KeptDescription.class)) {
            beans.add(bean);
        }
    }

    /**
     * Keeps the observer of a marked class.
     *
     * @param observer The observer
     */
    @Registration(types = KeptEvent.class)
    public void keepObserver(ObserverInfo observer) {
        if (observer.declaringClass().hasAnnotation(KeptDescription.class)) {
            observers.add(observer);
        }
    }

    /**
     * Reads what was kept into the report.
     *
     * @param components What the container is to have
     */
    @Synthesis
    public void synthesise(SyntheticComponents components) {
        if (beans.isEmpty() && observers.isEmpty()) {
            return;
        }
        synthesised = describe();
        components.addBean(KeptDescriptionReport.class)
            .type(KeptDescriptionReport.class)
            .withParam("text", synthesised)
            .createWith(KeptDescriptionReport.Creator.class);
    }

    /**
     * Reads what was kept again, and reports on the kept descriptions themselves.
     *
     * @param messages What reports
     */
    @Validation
    public void validate(Messages messages) {
        if (beans.isEmpty() && observers.isEmpty()) {
            return;
        }
        String validated = describe();
        if (!validated.equals(synthesised)) {
            messages.error("The kept descriptions read " + validated + " in validation and " + synthesised
                + " in synthesis");
            return;
        }
        for (BeanInfo bean : beans) {
            messages.info("Kept " + bean.declaringClass().simpleName(), bean);
            messages.info("Kept the declaration " + bean.declaringClass().simpleName(), bean.declaringClass());
        }
        for (ObserverInfo observer : observers) {
            messages.info("Kept an observer of " + observer.declaringClass().simpleName(), observer);
        }
    }

    private String describe() {
        List<String> described = new ArrayList<>();
        for (BeanInfo bean : beans) {
            described.add(attempt(() -> {
                ClassInfo declaring = bean.declaringClass();
                return declaring.simpleName()
                    + " types=" + sorted(bean.types().stream().map(KeptDescriptionsExtension::simple).toList())
                    + " qualifiers=" + sorted(bean.qualifiers().stream()
                        .map(qualifier -> simple(qualifier.name())).toList())
                    + " normalScope=" + bean.scope().isNormal()
                    + " methods=" + sorted(declaring.methods().stream().map(MethodInfo::name)
                        .filter(name -> name.startsWith("kept")).toList());
            }));
        }
        for (ClassInfo info : enhanced) {
            described.add(attempt(() -> "enhanced " + info.simpleName() + " methods=" + sorted(info.methods().stream()
                .map(MethodInfo::name).filter(name -> name.startsWith("kept")).toList())));
        }
        for (ObserverInfo observer : observers) {
            described.add(attempt(() -> {
                MethodInfo method = observer.observerMethod();
                List<String> parameters = new ArrayList<>();
                for (ParameterInfo parameter : method.parameters()) {
                    parameters.add(parameter.name() + ":" + simple(parameter.type()));
                }
                return "observer " + observer.declaringClass().simpleName() + "." + method.name()
                    + " event=" + simple(observer.eventType()) + " parameters=" + parameters;
            }));
        }
        return String.join("; ", described);
    }

    /**
     * What reading a kept description gave, or how it failed: a description that does not outlive its round fails
     * as it is read.
     */
    private static String attempt(Supplier<String> reading) {
        try {
            return reading.get();
        } catch (RuntimeException e) {
            return "failed " + e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private static String sorted(List<String> names) {
        // a Groovy class is a GroovyObject as well, which nothing here is about
        return names.stream().filter(name -> !name.equals("GroovyObject"))
            .collect(Collectors.toCollection(TreeSet::new)).toString();
    }

    private static String simple(Type type) {
        return simple(type.isClass() ? type.asClass().declaration().name() : type.toString());
    }

    private static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
