// Jazzer harness for the partial-JSON repair state machine.
//
// Written in Java, not Kotlin, purely so the fuzzing image does not need a Kotlin
// compiler on top of the JDK and Android SDK it already carries. It calls the same public
// API a consumer does; `PartialJson` is a Kotlin `object`, hence the INSTANCE reference.
//
// Why this target: fixJson is a hand-written character state machine over a mutable
// stack, and it is fed the least trustworthy input this library handles — a model's JSON,
// cut at an arbitrary byte because a stream ended mid-token. Everything else in the
// library either parses with kotlinx.serialization or never sees raw untrusted text.
//
// FixJsonFuzzTest covers the same contract with a seeded corpus, which is what runs in
// normal CI. This is the unbounded version: Jazzer's coverage feedback reaches the states
// a fixed corpus does not, and it catches the failure modes a unit test cannot express —
// non-termination, stack exhaustion on pathological nesting, and unbounded allocation.

import ai.torad.aisdk.PartialJson;
import ai.torad.aisdk.PartialJsonResult;
import ai.torad.aisdk.PartialJsonState;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;

public final class FixJsonFuzzer {

    private FixJsonFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String input = data.consumeRemainingAsString();
        if (input == null) {
            return;
        }

        // Any throw from here propagates and Jazzer reports it. fixJson's contract is
        // total over strings: it repairs what it can and returns "" otherwise, so an
        // exception escaping is itself the bug.
        PartialJson.INSTANCE.fixJson(input);

        // parsePartialJson's state is a contract consumers branch on while streaming.
        // A non-null value on a failure state (or the reverse) silently corrupts a
        // partially-decoded object rather than raising anything, so assert it here —
        // a crash-free-but-wrong result is exactly what a fuzzer should be told to hate.
        PartialJsonResult result = PartialJson.INSTANCE.parsePartialJson(input);
        PartialJsonState state = result.getState();
        boolean parsed =
                state == PartialJsonState.SuccessfulParse || state == PartialJsonState.RepairedParse;
        if (parsed != (result.getValue() != null)) {
            throw new IllegalStateException(
                    "parsePartialJson state/value disagree: state=" + state
                            + " value=" + result.getValue());
        }
    }
}
