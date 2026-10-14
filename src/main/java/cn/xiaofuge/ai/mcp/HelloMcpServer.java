package cn.xiaofuge.ai.mcp;

import cn.xiaofuge.ai.llm.Json;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java、零依赖的 MCP Server —— 手写 JSON-RPC over stdio。
 * <p>
 * 整个 MCP 协议对本 Server 而言只有两件事：
 * <ul>
 *   <li><b>tools/list</b> —— 把工具清单告诉客户端（模型靠它决定调哪个）；</li>
 *   <li><b>tools/call</b> —— 真的执行工具，把结果原路返回。</li>
 * </ul>
 * 再加上一次 initialize 握手，就是一个能被 Claude Desktop 接上的 MCP Server。
 * <p>
 * 通信格式：stdio + 换行分隔的 JSON（每条消息一行，行内不含裸换行）。
 * 注意 stdout 是协议通道，任何日志必须走 stderr，否则污染报文。
 */
public final class HelloMcpServer {

    /** 协议通道：显式 UTF-8，否则 Windows 默认 GBK 会把中文写坏。 */
    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    /** 日志通道：同样锁 UTF-8，且必须走 stderr —— stdout 是协议专用。 */
    private static final PrintStream ERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private static final String SERVER_NAME = "hello-mcp";
    private static final String SERVER_VERSION = "1.0.0";

    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        log("启动完成，等待客户端握手...");

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
                handle(line);
            } catch (Exception e) {
                log("处理消息失败: " + e);
            }
        }
        log("stdin 已关闭，退出");
    }

    // ------------------------------------------------------------------ 协议分发

    @SuppressWarnings("unchecked")
    private static void handle(String line) {
        Map<String, Object> req = (Map<String, Object>) Json.parse(line);
        String method = Json.str(req, "method");
        Object id = req.get("id");

        // 通知没有 id —— 按 JSON-RPC 规范，通知绝不回包
        if (id == null) {
            log("← 通知 " + method + "（不回包）");
            return;
        }

        log("← 请求 " + method + " (id=" + id + ")");
        switch (method) {
            case "initialize" -> reply(id, initializeResult(req));
            case "tools/list" -> reply(id, toolsListResult());
            case "tools/call" -> reply(id, toolsCallResult(req));
            case "ping" -> reply(id, Json.obj());
            default -> error(id, -32601, "Method not found: " + method);
        }
    }

    /** 握手：告诉客户端"我支持 tools 能力"，并回一个双方都认的协议版本。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> initializeResult(Map<String, Object> req) {
        Map<String, Object> params = (Map<String, Object>) req.get("params");
        String clientVersion = params == null ? null : Json.str(params, "protocolVersion");

        Map<String, Object> result = Json.obj();
        // 客户端给什么版本就回什么版本（教学版：不挑食）
        result.put("protocolVersion", clientVersion != null ? clientVersion : "2024-11-05");
        result.put("capabilities", Map.of("tools", Json.obj()));
        result.put("serverInfo", Map.of("name", SERVER_NAME, "version", SERVER_VERSION));
        return result;
    }

    /** tools/list —— 工具清单。这就是模型"看见"的全部世界。 */
    private static Map<String, Object> toolsListResult() {
        Map<String, Object> nameParam = Json.obj();
        nameParam.put("type", "string");
        nameParam.put("description", "要打招呼的人名");

        Map<String, Object> properties = Json.obj();
        properties.put("name", nameParam);

        Map<String, Object> schema = Json.obj();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));

        Map<String, Object> tool = Json.obj();
        tool.put("name", "say_hello");
        tool.put("description", "向指定的人打招呼");
        tool.put("inputSchema", schema);

        Map<String, Object> result = Json.obj();
        result.put("tools", List.of(tool));
        return result;
    }

    /** tools/call —— 真正执行工具，这是整个 Server 唯一"干活"的地方。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolsCallResult(Map<String, Object> req) {
        Map<String, Object> params = (Map<String, Object>) req.get("params");
        String name = params == null ? null : Json.str(params, "name");

        if (!"say_hello".equals(name)) {
            return callResult("未知工具: " + name, true);
        }

        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Json.obj();
        Object who = arguments.get("name");
        String greeting = "你好，" + (who == null ? "世界" : who) + "！这是来自纯 Java MCP 的问候 👋";

        log("→ 执行 say_hello(name=" + who + ")");
        return callResult(greeting, false);
    }

    private static Map<String, Object> callResult(String text, boolean isError) {
        Map<String, Object> content = Json.obj();
        content.put("type", "text");
        content.put("text", text);

        Map<String, Object> result = Json.obj();
        result.put("content", List.of(content));
        if (isError) result.put("isError", true);
        return result;
    }

    // ------------------------------------------------------------------ 收发

    private static void reply(Object id, Object result) {
        Map<String, Object> msg = Json.obj();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("result", result);
        send(msg);
    }

    private static void error(Object id, int code, String message) {
        Map<String, Object> err = Json.obj();
        err.put("code", code);
        err.put("message", message);

        Map<String, Object> msg = Json.obj();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("error", err);
        send(msg);
    }

    private static void send(Map<String, Object> msg) {
        // Json.write 会把换行转义成 \n，所以一行一条消息是安全的 —— 这就是 MCP stdio 的帧格式
        OUT.println(Json.write(msg));
        OUT.flush();
    }

    private static void log(String msg) {
        ERR.println("[" + SERVER_NAME + "] " + msg);
    }
}
