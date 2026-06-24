package org.kyj.llmmanager.util;

import org.junit.jupiter.api.Test;
import org.kyj.llmmanager.model.ArgSpec;
import org.kyj.llmmanager.model.ServiceDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandBuilderTest {

    @Test
    void splitCommandKeepsQuotedPathAsSingleArgument() {
        List<String> parts = CommandBuilder.splitCommand(
                "java -jar \"C:\\Program Files\\LLM Manager\\app.jar\" --port 8185");

        assertEquals(List.of(
                "java",
                "-jar",
                "C:\\Program Files\\LLM Manager\\app.jar",
                "--port",
                "8185"), parts);
    }

    @Test
    void buildStartCommandQuotesArgValuesWithWhitespace() {
        ServiceDefinition def = new ServiceDefinition();
        def.setStartCommand("python server.py");
        def.setArgSpecs(List.of(
                spec("modelDir", "--model-dir", "STRING", "C:\\models\\bge m3"),
                spec("debug", "--debug", "BOOLEAN", "true"),
                spec("port", "--port=", "INTEGER", "8185")));
        def.setArgValues(Map.of(
                "modelDir", "D:\\local models\\bge m3",
                "debug", "true",
                "port", "9090"));

        String command = CommandBuilder.buildStartCommand(def);

        assertEquals(List.of(
                "python",
                "server.py",
                "--model-dir",
                "D:\\local models\\bge m3",
                "--debug",
                "--port=9090"), CommandBuilder.splitCommand(command));
    }

    @Test
    void buildStartCommandSkipsBlankFlagSpecs() {
        ServiceDefinition def = new ServiceDefinition();
        def.setStartCommand("java -jar app.jar");
        def.setArgSpecs(List.of(
                spec("runtime.home", "", "STRING", "E:\\mes\\runtime"),
                spec("port", "--server.port=", "INTEGER", "20301")));
        def.setArgValues(Map.of(
                "runtime.home", "D:\\runtime",
                "port", "20302"));

        String command = CommandBuilder.buildStartCommand(def);

        assertEquals(List.of(
                "java",
                "-jar",
                "app.jar",
                "--server.port=20302"), CommandBuilder.splitCommand(command));
    }

    private ArgSpec spec(String name, String flag, String type, String defaultValue) {
        ArgSpec spec = new ArgSpec();
        spec.setName(name);
        spec.setFlag(flag);
        spec.setType(type);
        spec.setDefaultValue(defaultValue);
        spec.setEnabled(true);
        return spec;
    }
}
