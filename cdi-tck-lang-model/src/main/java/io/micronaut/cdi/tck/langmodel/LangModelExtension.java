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

/**
 * Hands the kit's verifier its own class, read through this implementation's language model, as the class
 * compiles.
 *
 * <p>This is the runner the kit itself ships for the reference implementation, written the same way: the class
 * is asked for in discovery, so that it is enhanced whether or not it is a bean, and verified in enhancement.
 * The verifier asserts, so the compiler runs with assertions enabled and a failed assertion fails the
 * compilation. That it ran at all is recorded into the file the build names, for the test to read.</p>
 */
public class LangModelExtension implements BuildCompatibleExtension {

    /**
     * The system property naming the file the extension records into.
     */
    public static final String REPORT_PROPERTY = "io.micronaut.cdi.tck.langModel.report";

    @Discovery
    public void addVerifier(ScannedClasses classes) {
        classes.add(LangModelVerifier.class.getName());
    }

    /**
     * The sections of the verifier, each verifying the class of one field of the verifier, in the order the
     * verifier runs them. Run one by one when the whole fails, so that every failing section is reported rather
     * than the first alone.
     */
    private static final String[][] SECTIONS = {
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

    @Enhancement(types = LangModelVerifier.class)
    public void verify(ClassInfo clazz) {
        try {
            LangModelVerifier.verify(clazz);
        } catch (AssertionError | RuntimeException e) {
            // the compiler reports only the message of what an extension threw, and an assertion has none: the
            // stack trace of each failing section, which names the assertion, goes into the report for the
            // build to show
            StringBuilder report = new StringBuilder("failed\n").append(trace(e));
            for (String[] section : SECTIONS) {
                try {
                    verifySection(clazz, section[0], section[1]);
                } catch (Throwable failure) {
                    report.append("\n--- ").append(section[0]).append('\n').append(trace(failure));
                }
            }
            report(report.toString());
            throw e;
        }
        report("verified " + clazz.name());
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
