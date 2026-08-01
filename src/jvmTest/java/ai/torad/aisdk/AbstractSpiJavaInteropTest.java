package ai.torad.aisdk;

import org.junit.Test;

import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

/**
 * Guards the ABI trap that shipped once: `@JvmSynthetic` applied to a public ABSTRACT member.
 *
 * A codemod put it on 52 body-less declarations across 25 files. On an abstract member it emits
 * ACC_ABSTRACT|ACC_SYNTHETIC, and javac SKIPS synthetic members when checking that a class
 * implements everything abstract — so a Java class implementing the interface compiled clean and
 * then threw java.lang.AbstractMethodError on the first call. Both halves were reproduced against
 * the built jar before the fix.
 *
 * This file is the red proof and the permanent wall. It does not assert anything at runtime: the
 * assertion IS that it compiles. Each class below implements a public SPI interface and overrides
 * the member in question, so if `@JvmSynthetic` is ever reapplied there, javac fails with
 * "method does not override or implement a method from a supertype" and the build goes red — which
 * is exactly what a fixture-less codemod would otherwise slip past.
 *
 * Keep these implementations minimal; they exist to be COMPILED, not to be correct.
 */
public final class AbstractSpiJavaInteropTest {

    /** `StopCondition.shouldStop` — the member the original repro used. */
    static final class JavaStopCondition implements StopCondition {
        @Override
        public Object shouldStop(LoopState state, Continuation<? super Boolean> continuation) {
            return Boolean.TRUE;
        }
    }

    /** `LanguageModel.stream` — a non-suspend Flow return, where the rule's Continuation
     *  rationale never applied in the first place. */
    abstract static class JavaLanguageModel implements LanguageModel {
        @Override
        public abstract Flow<StreamEvent> stream(LanguageModelCallParams params);
    }

    @Test
    public void abstractSpiMembersRemainVisibleToJavaImplementors() {
        // Compiling this file is the test. Instantiate one to keep the class reachable so the
        // compiler cannot elide it, and to prove the implementation actually satisfies the
        // interface at class-load time rather than only at javac time.
        StopCondition condition = new JavaStopCondition();
        org.junit.Assert.assertNotNull(condition);
    }
}
