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
package io.micronaut.cdi.errorprone;

import com.google.errorprone.CompilationTestHelper;
import org.junit.jupiter.api.Test;

/**
 * Every thread local is reported, static or not, whatever subtype it is of and however it is made; what is merely
 * named alike, or a thread itself, is not.
 */
class ThreadLocalForbiddenTest {

    private final CompilationTestHelper helper =
        CompilationTestHelper.newInstance(ThreadLocalForbidden.class, getClass());

    @Test
    void aStaticFieldAndItsInitialValueAreReported() {
        helper.addSourceLines("Holder.java",
                "class Holder {",
                "  // BUG: Diagnostic contains: ThreadLocal is not used in Micronaut CDI",
                "  private static final ThreadLocal<String> CURRENT =",
                "      // BUG: Diagnostic contains: PropagatedContext",
                "      ThreadLocal.withInitial(() -> \"\");",
                "}")
            .doTest();
    }

    @Test
    void aFieldCreatedWithNewIsReportedTwice() {
        helper.addSourceLines("Holder.java",
                "class Holder {",
                "  // BUG: Diagnostic contains: ThreadLocalForbidden",
                "  private final ThreadLocal<Boolean> begunHere =",
                "      // BUG: Diagnostic contains: ThreadLocalForbidden",
                "      new ThreadLocal<>();",
                "}")
            .doTest();
    }

    @Test
    void anInheritableThreadLocalAndAnAnonymousSubclassAreReported() {
        helper.addSourceLines("Holder.java",
                "class Holder {",
                "  // BUG: Diagnostic contains: ThreadLocalForbidden",
                "  static final InheritableThreadLocal<Integer> DEPTH =",
                "      // BUG: Diagnostic contains: ThreadLocalForbidden",
                "      new InheritableThreadLocal<>();",
                "  static Object initial() {",
                "    // BUG: Diagnostic contains: ThreadLocalForbidden",
                "    return new ThreadLocal<Integer>() {",
                "      @Override protected Integer initialValue() { return 0; }",
                "    };",
                "  }",
                "}")
            .doTest();
    }

    @Test
    void aLocalVariableAndAParameterAreReported() {
        helper.addSourceLines("Holder.java",
                "class Holder {",
                "  // BUG: Diagnostic contains: ThreadLocalForbidden",
                "  static void clear(ThreadLocal<?> state) {",
                "    // BUG: Diagnostic contains: ThreadLocalForbidden",
                "    ThreadLocal<?> same = state;",
                "    same.remove();",
                "  }",
                "}")
            .doTest();
    }

    @Test
    void aSubclassDeclaredElsewhereIsReported() {
        helper.addSourceLines("Depth.java",
                "class Depth extends ThreadLocal<Integer> {",
                "}")
            .addSourceLines("Holder.java",
                "class Holder {",
                "  // BUG: Diagnostic contains: ThreadLocalForbidden",
                "  final Depth depth =",
                "      // BUG: Diagnostic contains: ThreadLocalForbidden",
                "      new Depth();",
                "}")
            .doTest();
    }

    @Test
    void whatIsNoThreadLocalIsNotReported() {
        helper.addSourceLines("Holder.java",
                "import java.util.function.Supplier;",
                "class Holder {",
                "  static final class ThreadLocalish {",
                "    static <T> ThreadLocalish withInitial(Supplier<T> initial) { return new ThreadLocalish(); }",
                "  }",
                "  private final ThreadLocalish lookalike = ThreadLocalish.withInitial(() -> \"\");",
                "  private final Thread owner = Thread.currentThread();",
                "  private final java.util.concurrent.atomic.AtomicReference<String> held =",
                "      new java.util.concurrent.atomic.AtomicReference<>();",
                "}")
            .doTest();
    }

    @Test
    void aSuppressedThreadLocalIsNotReported() {
        helper.addSourceLines("Holder.java",
                "class Holder {",
                "  @SuppressWarnings(\"ThreadLocalForbidden\")",
                "  private static final ThreadLocal<String> CURRENT = ThreadLocal.withInitial(() -> \"\");",
                "}")
            .doTest();
    }
}
