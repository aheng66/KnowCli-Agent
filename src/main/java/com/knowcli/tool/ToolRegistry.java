package com.knowcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowcli.llm.DeepSeekClient;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;

    // 注册工具构造方法
    public ToolRegistry() {
        registerFileTools();
        registerShellTools();
        registerCodeTools();
    }

    // 工具类 （工具名、工具介绍、工具参数、工具执行逻辑）
    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {
    }

    // 工具的参数类 （参数名、参数类型、参数介绍、是否必须）
    private record Param(String name, String type, String description, boolean required) {
    }

    // 工具的执行逻辑
    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }


    // 注册文件工具
    private void registerFileTools() {
        // read_file工具
        tools.put(
                "read_file", new Tool(
                        "read_file",
                        "读取文件内容，用于查看代码、配置文件等",
                        createParameters(
                                new Param("path", "string", "文件路径", true)
                        ),
                        args -> {
                            String path = args.get("path");
                            try {
                                String content = Files.readString(Path.of(path));
                                return "文件内容:\n" + content;
                            } catch (Exception e) {
                                return "读取文件失败: " + e.getMessage();
                            }
                        }
                ));

        // write_file 工具
        tools.put(
                "write_file", new Tool(
                        "write_file",
                        "写入文件(仅限项目根目录内，单文件不超过最大值)",
                        createParameters(
                                new Param("path", "string", "文件路径", true),
                                new Param("content", "string", "文件内容", true)
                        ),
                        args -> {
                            String pathValue = args.get("path");
                            String content = args.get("content");
                            if (pathValue == null || pathValue.isBlank()) {
                                return "写入文件失败: 参数 path 不能为空";
                            }
                            if (content == null) {
                                return "写入文件失败: 参数 content 不能为空";
                            }

                            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
                            if (contentBytes.length > MAX_WRITE_FILE_BYTES) {
                                return "写入文件失败: 文件内容超过 5MB 限制";
                            }
                            try {
                                Path target = prepareWriteTarget(pathValue);
                                Files.write(target, contentBytes);
                                return "文件已写入: " + pathValue;
                            } catch (Exception e) {
                                return "写入文件失败: " + e.getMessage();
                            }
                        }
                ));

        //list_dir 工具
        tools.put(
                "list_dir", new Tool(
                        "list_dir",
                        "列出目录内容",
                        createParameters(
                                new Param("path", "string", "目录路径，默认为项目根目录", false)
                        ),
                        args -> listDirectory(args.get("path"))
                ));
    }

    // 注册命令行执行工具
    private void registerShellTools() {
        // execute_command工具注册
        tools.put("execute_command", new Tool(
                "execute_command",
                "执行Shell命令，用于编译、运行、Git操作等",
                createParameters(
                        new Param("command", "string", "要执行的Shell命令", true)
                ),
                args -> executeCommand(args.get("command"))
        ));
    }

    // 注册代码相关工具
    private void registerCodeTools() {
        //create_project 工具注册
        tools.put("create_project", new Tool(
                "create_project",
                "创建一个项目，并返回项目路径",
                createParameters(
                        new Param("type", "string", "项目类型: java、python 或 node", true),
                        new Param("name", "string", "项目名称，使用小写字母、数字和连字符", true),
                        new Param("path", "string", "父目录，默认为项目根目录", false)
                ),
                args -> createProject(
                        args.get("type"),
                        args.get("name"),
                        args.get("path")
                )

        ));
    }

    /**
     * 创建项目，支持Java、Python、Node.js
     *
     * @param typeValue       项目类型
     * @param name            项目名称
     * @param parentPathValue 父目录，默认为项目根目录
     * @return 创建项目的路径
     */
    private String createProject(String typeValue, String name, String parentPathValue) {
        if (typeValue == null || typeValue.isBlank()) {
            return "创建项目失败: 参数 type 不能为空";
        }
        if (name == null || !name.matches("[a-z][a-z0-9-]*")) {
            return "创建项目失败: 项目名称格式无效，应匹配 [a-z][a-z0-9-]*";
        }

        String type = typeValue.trim().toLowerCase(Locale.ROOT);
        if (!type.equals("java") && !type.equals("python") && !type.equals("node")) {
            return "创建项目失败: 不支持的项目类型: " + typeValue;
        }

        Path project = null;
        boolean projectCreated = false;
        try {
            Path projectRoot = Path.of("").toAbsolutePath().normalize();
            Path projectRootReal = projectRoot.toRealPath();
            Path parentPath = parentPathValue == null || parentPathValue.isBlank()
                    ? Path.of("")
                    : Path.of(parentPathValue);

            if (parentPath.isAbsolute()) {
                return "创建项目失败: path 必须是项目根目录内的相对路径";
            }

            project = projectRoot.resolve(parentPath).resolve(name).normalize();
            if (!project.startsWith(projectRoot)) {
                return "创建项目失败: 仅允许在项目根目录内创建项目";
            }
            if (Files.exists(project)) {
                return "创建项目失败: 目标路径已存在: " + project;
            }

            Path existingAncestor = project.getParent();
            while (existingAncestor != null && !Files.exists(existingAncestor)) {
                existingAncestor = existingAncestor.getParent();
            }
            if (existingAncestor == null
                    || !existingAncestor.toRealPath().startsWith(projectRootReal)) {
                return "创建项目失败: 仅允许在项目根目录内创建项目";
            }

            Files.createDirectories(project.getParent());
            Files.createDirectory(project);
            projectCreated = true;
            switch (type) {
                case "java" -> createJavaProject(project, name);
                case "python" -> createPythonProject(project, name);
                case "node" -> createNodeProject(project, name);
                default -> throw new IllegalStateException("未知项目类型: " + type);
            }

            String displayType = switch (type) {
                case "java" -> "Java";
                case "python" -> "Python";
                case "node" -> "Node.js";
                default -> type;
            };
            return displayType + " 项目创建成功: " + project;
        } catch (Exception exception) {
            if (projectCreated) {
                deleteRecursively(project);
            }
            return "创建项目失败: " + exception.getMessage();
        }
    }

    //创建Java项目
    private void createJavaProject(Path project, String name) throws IOException {
        writeProjectFile(project, "pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <properties>
                        <maven.compiler.release>17</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <junit.version>5.10.2</junit.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>${junit.version}</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.2.5</version>
                            </plugin>
                            <plugin>
                                <groupId>org.codehaus.mojo</groupId>
                                <artifactId>exec-maven-plugin</artifactId>
                                <version>3.3.0</version>
                                <configuration>
                                    <mainClass>com.example.Main</mainClass>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(name));
        writeProjectFile(project, "src/main/java/com/example/Main.java", """
                package com.example;
                
                public final class Main {
                    private Main() {
                    }
                
                    public static String greet() {
                        return "Hello, world!";
                    }
                
                    public static void main(String[] args) {
                        System.out.println(greet());
                    }
                }
                """);
        writeProjectFile(project, "src/test/java/com/example/MainTest.java", """
                package com.example;
                
                import org.junit.jupiter.api.Test;
                
                import static org.junit.jupiter.api.Assertions.assertEquals;
                
                class MainTest {
                    @Test
                    void greetsTheWorld() {
                        assertEquals("Hello, world!", Main.greet());
                    }
                }
                """);
        writeProjectFile(project, ".gitignore", """
                target/
                .idea/
                *.iml
                """);
        writeProjectFile(project, "README.md", """
                # %s
                
                ## 测试
                
                ```shell
                mvn test
                ```
                
                ## 运行
                
                ```shell
                mvn exec:java
                ```
                """.formatted(name));
    }

    //创建Python项目
    private void createPythonProject(Path project, String name) throws IOException {
        writeProjectFile(project, "main.py", """
                def greet():
                    return "Hello, world!"
                
                
                if __name__ == "__main__":
                    print(greet())
                """);
        writeProjectFile(project, "requirements.txt", "");
        writeProjectFile(project, "tests/test_main.py", """
                import unittest
                
                from main import greet
                
                
                class MainTest(unittest.TestCase):
                    def test_greet(self):
                        self.assertEqual("Hello, world!", greet())
                
                
                if __name__ == "__main__":
                    unittest.main()
                """);
        writeProjectFile(project, ".gitignore", """
                __pycache__/
                *.py[cod]
                .venv/
                .pytest_cache/
                """);
        writeProjectFile(project, "README.md", """
                # %s
                
                ## 测试
                
                ```shell
                python -m unittest discover
                ```
                
                ## 运行
                
                ```shell
                python main.py
                ```
                """.formatted(name));
    }

    //创建Node项目
    private void createNodeProject(Path project, String name) throws IOException {
        writeProjectFile(project, "package.json", """
                {
                  "name": "%s",
                  "version": "1.0.0",
                  "private": true,
                  "type": "module",
                  "scripts": {
                    "start": "node src/index.js",
                    "test": "node --test"
                  }
                }
                """.formatted(name));
        writeProjectFile(project, "src/index.js", """
                import { pathToFileURL } from "node:url";
                
                export function greet() {
                  return "Hello, world!";
                }
                
                if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
                  console.log(greet());
                }
                """);
        writeProjectFile(project, "test/index.test.js", """
                import test from "node:test";
                import assert from "node:assert/strict";
                
                import { greet } from "../src/index.js";
                
                test("greets the world", () => {
                  assert.equal(greet(), "Hello, world!");
                });
                """);
        writeProjectFile(project, ".gitignore", """
                node_modules/
                coverage/
                .env
                """);
        writeProjectFile(project, "README.md", """
                # %s
                
                ## 测试
                
                ```shell
                npm test
                ```
                
                ## 运行
                
                ```shell
                npm start
                ```
                """.formatted(name));
    }

    private void writeProjectFile(Path project, String relativePath, String content)
            throws IOException {
        Path file = project.resolve(relativePath).normalize();
        if (!file.startsWith(project)) {
            throw new IOException("模板文件路径超出项目目录: " + relativePath);
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    // 删除回滚
    private void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 尽力回滚，不覆盖原始创建异常。
                }
            });
        } catch (IOException ignored) {
            // 尽力回滚，不覆盖原始创建异常。
        }
    }

    /**
     * 写入文件时的路径检查及父目录创建
     *
     * @param pathValue 路径
     * @return 目标路径
     */
    private Path prepareWriteTarget(String pathValue) throws IOException {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path realRoot = root.toRealPath();
        Path target = root.resolve(pathValue).toAbsolutePath().normalize();

        if (!target.startsWith(root)) {
            throw new IOException("仅允许写入项目根目录内的文件");
        }

        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("无法确定父目录: " + target);
        }

        Path existingAncestor = parent;
        while (existingAncestor != null && !Files.exists(existingAncestor)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null
                || !existingAncestor.toRealPath().startsWith(realRoot)) {
            throw new IOException("仅允许写入项目根目录内的文件");
        }

        Files.createDirectories(parent);
        if (!parent.toRealPath().startsWith(realRoot)) {
            throw new IOException("仅允许写入项目根目录内的文件");
        }
        if (Files.isSymbolicLink(target)) {
            throw new IOException("不允许写入符号链接");
        }
        if (Files.exists(target) && !Files.isRegularFile(target)) {
            throw new IOException("目标路径不是普通文件: " + target);
        }
        return target;
    }

    /**
     * 列出项目根目录内指定目录的当前层内容。
     *
     * @param pathValue 目录路径，为空时使用项目根目录
     * @return 目录内容或错误信息
     */
    private String listDirectory(String pathValue) {
        final int maxEntries = 1000;

        try {
            Path projectRoot = Path.of("").toAbsolutePath().normalize();
            Path projectRootReal = projectRoot.toRealPath();
            Path target = pathValue == null || pathValue.isBlank()
                    ? projectRoot
                    : projectRoot.resolve(pathValue).toAbsolutePath().normalize();

            if (!target.startsWith(projectRoot)) {
                return "列出目录失败: 仅允许访问项目根目录内的目录";
            }

            Path directory = target.toRealPath();
            if (!directory.startsWith(projectRootReal)) {
                return "列出目录失败: 仅允许访问项目根目录内的目录";
            }
            if (!Files.isDirectory(directory)) {
                return "列出目录失败: 目标路径不是目录: " + target;
            }

            List<Path> entries;
            try (Stream<Path> stream = Files.list(directory)) {
                entries = stream
                        .sorted(Comparator
                                .comparing(
                                        (Path path) -> path.getFileName().toString(),
                                        String.CASE_INSENSITIVE_ORDER
                                )
                                .thenComparing(path -> path.getFileName().toString()))
                        .limit(maxEntries + 1L)
                        .toList();
            }

            String displayPath = pathValue == null || pathValue.isBlank()
                    ? "."
                    : pathValue;
            if (entries.isEmpty()) {
                return "目录内容: " + displayPath + "\n(目录为空)";
            }

            boolean truncated = entries.size() > maxEntries;
            StringBuilder result = new StringBuilder("目录内容: ")
                    .append(displayPath)
                    .append('\n');
            entries.stream()
                    .limit(maxEntries)
                    .forEach(entry -> result
                            .append(directoryEntryType(entry))
                            .append(' ')
                            .append(entry.getFileName())
                            .append('\n'));

            if (truncated) {
                result.append("[目录条目超过 1000 个，已截断]\n");
            }
            return result.toString().stripTrailing();
        } catch (Exception exception) {
            return "列出目录失败: " + exception.getMessage();
        }
    }

    private String directoryEntryType(Path entry) {
        if (Files.isSymbolicLink(entry)) {
            return "[LINK]";
        }
        if (Files.isDirectory(entry)) {
            return "[DIR]";
        }
        if (Files.isRegularFile(entry)) {
            return "[FILE]";
        }
        return "[OTHER]";
    }

    /**
     * 执行Shell命令
     *
     * @param command 命令
     * @return 命令执行结果
     */
    private String executeCommand(String command) {
        if (command == null || command.isBlank()) {
            return "命令执行失败: 参数 command 不能为空";
        }

        boolean windows = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        ProcessBuilder processBuilder = windows
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("/bin/sh", "-c", command);
        processBuilder.directory(Path.of("").toAbsolutePath().normalize().toFile());
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            int maxOutputBytes = 1024 * 1024;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            AtomicBoolean truncated = new AtomicBoolean(false);
            AtomicReference<IOException> readError = new AtomicReference<>();

            Thread outputReader = new Thread(() -> {
                try (InputStream stream = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = stream.read(buffer)) != -1) {
                        int remaining = maxOutputBytes - output.size();
                        if (remaining > 0) {
                            output.write(buffer, 0, Math.min(bytesRead, remaining));
                        }
                        if (bytesRead > remaining) {
                            truncated.set(true);
                        }
                    }
                } catch (IOException exception) {
                    readError.set(exception);
                }
            }, "execute-command-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finished;
            try {
                finished = process.waitFor(60, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                return "命令执行失败: 执行线程被中断";
            }

            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                    outputReader.join(5000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }

                String partialOutput = output.toString(Charset.defaultCharset());
                return partialOutput.isEmpty()
                        ? "命令执行超时: 已超过 60 秒"
                        : "命令执行超时: 已超过 60 秒\n输出:\n" + partialOutput;
            }

            try {
                outputReader.join(5000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return "命令执行失败: 读取输出时线程被中断";
            }

            if (readError.get() != null) {
                return "命令执行失败: 读取命令输出失败: "
                        + readError.get().getMessage();
            }

            String commandOutput = output.toString(Charset.defaultCharset());
            if (commandOutput.isEmpty()) {
                commandOutput = "(无输出)";
            }
            if (truncated.get()) {
                commandOutput += "\n[输出超过 1 MiB，已截断]";
            }
            return "退出码: " + process.exitValue()
                    + "\n输出:\n" + commandOutput;
        } catch (IOException exception) {
            return "命令执行失败: " + exception.getMessage();
        }
    }

    /**
     * 创建JsonNode格式的工具参数
     *
     * @param params 参数，可以接收多个 Param 对象
     * @return root JsonNode
     */
    private JsonNode createParameters(Param... params) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");
        var required = root.putArray("required");

        for (Param param : params) {
            ObjectNode property =
                    properties.putObject(param.name());
            property.put("type", param.type());
            property.put("description", param.description());

            if (param.required()) {
                required.add(param.name());
            }
        }
        // 工具参数中不允许出现未在 properties 里声明的额外字段
        root.put("additionalProperties", false);
        return root;
    }

    // 将注册工具转换成 DeepSeekClient.Tool
    public List<DeepSeekClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .sorted(Comparator.comparing(Tool::name))
                .map(tool -> new DeepSeekClient.Tool(
                        tool.name(),
                        tool.description(),
                        tool.parameters()
                ))
                .toList();
    }

    /**
     * 执行工具调用
     * @param name 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     */
    public String executeToolCalls(String name, String arguments) {
        if (name == null || name.isBlank()) {
            return "工具执行失败: 工具名称不能为空";
        }

        Tool tool = tools.get(name);
        if (tool == null) {
            return "工具执行失败: 未知工具: " + name;
        }

        try {
            JsonNode argumentsJson = mapper.readTree(
                    arguments == null || arguments.isBlank() ? "{}" : arguments
            );
            if (!argumentsJson.isObject()) {
                return "工具执行失败: arguments 必须是 JSON 对象";
            }

            Map<String, String> parsedArguments = new HashMap<>();
            argumentsJson.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                parsedArguments.put(
                        entry.getKey(),
                        value == null || value.isNull()
                                ? null
                                : value.isTextual() ? value.asText() : value.toString()
                );
            });
            return tool.executor().execute(parsedArguments);
        } catch (IOException exception) {
            return "工具执行失败: arguments 不是有效 JSON: " + exception.getMessage();
        } catch (RuntimeException exception) {
            return "工具执行失败: " + exception.getMessage();
        }
    }
}
