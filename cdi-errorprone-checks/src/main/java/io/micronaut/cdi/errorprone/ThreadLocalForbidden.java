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

import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;

import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;

/**
 * Forbids {@link ThreadLocal} in Micronaut CDI: a field, a local variable or a parameter of the type or of a subtype
 * such as {@link InheritableThreadLocal}, an instance created of either, and {@link ThreadLocal#withInitial}.
 *
 * <p>State that belongs to a thread does not reach the thread work is carried to, survives the container that
 * kept it, and is invisible to whoever reads the code that resolves a bean. What a request, a resolution or a
 * lookup needs is held where Micronaut holds it: in its {@code PropagatedContext}, which follows the work, or in
 * the resolution context, which is the resolution. Error Prone's own {@code ThreadLocalUsage} flags only a thread
 * local that is not static; this flags every one.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@BugPattern(
    summary = "ThreadLocal is not used in Micronaut CDI: thread state belongs to Micronaut's PropagatedContext or to "
        + "the resolution context",
    severity = ERROR
)
public final class ThreadLocalForbidden extends BugChecker
    implements BugChecker.VariableTreeMatcher, BugChecker.NewClassTreeMatcher,
    BugChecker.MethodInvocationTreeMatcher {

    private static final String THREAD_LOCAL = "java.lang.ThreadLocal";

    private static final Matcher<Tree> OF_THREAD_LOCAL = Matchers.isSubtypeOf(THREAD_LOCAL);

    private static final Matcher<ExpressionTree> WITH_INITIAL =
        Matchers.staticMethod().onClass(THREAD_LOCAL).named("withInitial");

    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
        return OF_THREAD_LOCAL.matches(tree, state) ? describeMatch(tree) : Description.NO_MATCH;
    }

    @Override
    public Description matchNewClass(NewClassTree tree, VisitorState state) {
        return OF_THREAD_LOCAL.matches(tree, state) ? describeMatch(tree) : Description.NO_MATCH;
    }

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        return WITH_INITIAL.matches(tree, state) ? describeMatch(tree) : Description.NO_MATCH;
    }
}
