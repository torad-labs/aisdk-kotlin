package ai.torad.aisdk;

import kotlin.jvm.internal.StringCompanionObject;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class FactoryJvmOverloadsInteropTest {
    @Test
    public void toolFactoryShortOverloadIsCallableFromJava() {
        KSerializer<String> stringSerializer = BuiltinSerializersKt.serializer(StringCompanionObject.INSTANCE);

        Tool<String, String, Object> tool = ToolKt.Tool(
            "echo",
            "Echo input",
            stringSerializer,
            stringSerializer,
            (context, input, continuation) -> input
        );

        assertEquals("echo", tool.getName());
        assertEquals("Echo input", tool.getDescription());
        assertNull(tool.getStrict());
        assertTrue(tool.getInputExamples().isEmpty());
        assertTrue(tool.getMetadata().isEmpty());
    }

    @Test
    public void generatedFileShortOverloadIsCallableFromJava() {
        FileData.Base64 data = new FileData.Base64("YWJj", "text/plain", "note.txt");

        GeneratedFile file = MediaModelsKt.GeneratedFile(data);

        assertEquals("text/plain", file.getMediaType());
        assertEquals("YWJj", file.getBase64());
        assertEquals("note.txt", file.getFilename());
        assertNull(file.getUrl());
    }
}
