/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.tck.langmodel;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.jboss.cdi.lang.model.tck.LangModelVerifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The build compatible extension that runs the kit's language model verifier against this module's model as the
 * kit's classes compile, and records what it found, section by section, for the test to read.
 *
 * <p>The verifier is run one section at a time: the compiler reports no more than the message of what an
 * extension threw, an assertion has none, and a section the model cannot satisfy yet must not hide the ones it
 * can. Nothing is thrown, so the compilation goes on whatever was found; the test holds each section to what is
 * expected of it.</p>
 */
public class LangModelExtension implements BuildCompatibleExtension {

    /**
     * The system property naming the file the extension records into.
     */
    public static final String REPORT_PROPERTY = "io.micronaut.cdi.tck.langModel.report";

    /**
     * The line of the report that names the class the verifier was handed.
     */
    public static final String VERIFIED = "verified ";

    /**
     * The prefix of a report line that names a section and how it ended.
     */
    public static final String SECTION = "section ";

    /**
     * The section of the verifier that is not one of a field's class: the package annotation.
     */
    public static final String PACKAGE_SECTION = "PackageAnnotation";

    /**
     * The sections of the verifier, each verifying the class of one field of the verifier, in the order the
     * verifier runs them.
     */
    static final String[][] SECTIONS = {
        {"AnnotatedTypes", "annotatedTypes"}, {"AnnotatedSuperTypes", "annotatedSuperTypes"},
        {"AnnotatedThrowsTypes", "annotatedThrowsTypes"}, {"AnnotatedReceiverTypes", "annotatedReceiverTypes"},
        {"AnnotationInstances", "annotationInstances"}, {"PlainClassMembers$Verifier", "plainClassMembers"},
        {"InterfaceMembers$Verifier", "interfaceMembers"}, {"AnnotationMembers$Verifier", "annotationMembers"},
        {"EnumMembers$Verifier", "enumMembers"}, {"InheritedMethods$Verifier", "inheritedMethods"},
        {"InheritedFields$Verifier", "inheritedFields"}, {"InheritedAnnotations", "inheritedAnnotations"},
        {"JavaLangObjectMethods$Verifier", "javaLangObjectMethods"}, {"PrimitiveTypes", "primitiveTypes"},
        {"BridgeMethods", "bridgeMethods"}, {"RepeatableAnnotations", "repeatableAnnotations"},
        {"DefaultConstructors", "defaultConstructors"}, {"Equality", "equality"},
    };

    @Discovery
    public void addVerifier(ScannedClasses classes) {
        classes.add(LangModelVerifier.class.getName());
    }

    @Enhancement(types = LangModelVerifier.class)
    public void verify(ClassInfo clazz) {
        StringBuilder report = new StringBuilder(VERIFIED).append(clazz.name()).append('\n');
        Map<String, Throwable> failures = new LinkedHashMap<>();
        for (String[] section : SECTIONS) {
            try {
                verifySection(clazz, section[0], section[1]);
                report.append(SECTION).append(section[0]).append(" passed\n");
            } catch (Throwable failure) {
                report.append(SECTION).append(section[0]).append(" failed\n");
                failures.put(section[0], failure);
            }
        }
        try {
            verifyPackageAnnotation(clazz);
            report.append(SECTION).append(PACKAGE_SECTION).append(" passed\n");
        } catch (Throwable failure) {
            report.append(SECTION).append(PACKAGE_SECTION).append(" failed\n");
            failures.put(PACKAGE_SECTION, failure);
        }
        // the stack trace of each failing section, which names the assertion, for the build to show
        failures.forEach((section, failure) ->
            report.append("\n--- ").append(section).append('\n').append(trace(failure)));
        report(report.toString());
    }

    private static void verifySection(ClassInfo clazz, String verifier, String field) throws Throwable {
        ClassInfo subject = (ClassInfo) Class.forName("org.jboss.cdi.lang.model.tck.LangModelUtils")
            .getMethod("classOfField", ClassInfo.class, String.class).invoke(null, clazz, field);
        try {
            Class.forName("org.jboss.cdi.lang.model.tck." + verifier).getMethod("verify", ClassInfo.class)
                .invoke(null, subject);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * The verifier's own checks on the class it is handed, which its entry point runs before and after the
     * sections: that assertions are on, that only runtime annotations are reported, and the package's annotation.
     */
    private static void verifyPackageAnnotation(ClassInfo clazz) throws Throwable {
        for (String check : new String[] {"ensureAssertionsEnabled", "ensureOnlyRuntimeAnnotations",
                                          "verifyPackageAnnotation"}) {
            java.lang.reflect.Method method = check.startsWith("ensureAssertions")
                ? LangModelVerifier.class.getDeclaredMethod(check)
                : LangModelVerifier.class.getDeclaredMethod(check, ClassInfo.class);
            method.setAccessible(true);
            try {
                if (method.getParameterCount() == 0) {
                    method.invoke(null);
                } else {
                    method.invoke(null, clazz);
                }
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static String trace(Throwable e) {
        java.io.StringWriter trace = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(trace));
        // every exception of the chain with the frames of the verifier and of this module's language model: the
        // rest is the compiler invoking the extension
        return java.util.Arrays.stream(trace.toString().split("\n"))
            .filter(line -> !line.startsWith("\tat ") || line.contains("org.jboss.cdi")
                || line.contains("io.micronaut.cdi.processor"))
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static void report(String text) {
        String report = System.getProperty(REPORT_PROPERTY);
        if (report != null) {
            try {
                Files.writeString(Path.of(report), text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
