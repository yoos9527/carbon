package com.carbon.framework.script;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GroovySandbox} 的沙箱边界与正常执行用例。
 *
 * @author carbon
 */
class GroovySandboxTest {

    private GroovySandbox sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new GroovySandbox(GroovySandboxOptions.defaults(), null);
    }

    @AfterEach
    void tearDown() {
        sandbox.close();
    }

    private Map<String, Object> newCtx() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("request", new LinkedHashMap<String, Object>());
        ctx.put("vars", new HashMap<String, Object>());
        ctx.put("input", Map.of("orderId", "A-1"));
        return ctx;
    }

    @Test
    @DisplayName("脚本可原地改写 ctx.vars")
    @SuppressWarnings("unchecked")
    void scriptMutatesVars() {
        Map<String, Object> ctx = newCtx();

        ScriptResult result = sandbox.execute("ctx.vars.total = 1 + 2", ctx, "trace-1");

        assertEquals(3, ((Map<String, Object>) ctx.get("vars")).get("total"));
        assertFalse(result.skipped());
        assertEquals(GroovySandbox.hashOf("ctx.vars.total = 1 + 2"), result.scriptHash());
        assertEquals(result.ctx(), ctx);
    }

    @Test
    @DisplayName("白名单工具可用：sha256 / json / jsonpath")
    @SuppressWarnings("unchecked")
    void utilsAreAvailable() {
        Map<String, Object> ctx = newCtx();

        sandbox.execute("""
                ctx.vars.digest = utils.sha256('abc')
                ctx.vars.json = utils.json([a: 1, b: 'x'])
                ctx.vars.picked = utils.jsonpath('{"data":{"id":9}}', '$.data.id')
                """, ctx, "trace-2");

        Map<String, Object> vars = (Map<String, Object>) ctx.get("vars");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", vars.get("digest"));
        assertEquals("{\"a\":1,\"b\":\"x\"}", vars.get("json"));
        assertEquals(9, vars.get("picked"));
    }

    @Test
    @DisplayName("白名单包可导入：java.time / java.util")
    @SuppressWarnings("unchecked")
    void whitelistedImportsWork() {
        Map<String, Object> ctx = newCtx();

        sandbox.execute("""
                import java.time.LocalDate
                import java.time.format.DateTimeFormatter
                def d = LocalDate.of(2024, 3, 1)
                ctx.vars.text = d.format(DateTimeFormatter.ofPattern('yyyy/MM/dd'))
                ctx.vars.sorted = ['b', 'a'].sort()
                """, ctx, "trace-3");

        Map<String, Object> vars = (Map<String, Object>) ctx.get("vars");
        assertEquals("2024/03/01", vars.get("text"));
        assertEquals(java.util.List.of("a", "b"), vars.get("sorted"));
    }

    @Test
    @DisplayName("ctx.log(...) 可用且不抛异常")
    void logIsAvailable() {
        Map<String, Object> ctx = newCtx();
        sandbox.execute("ctx.log('hello from script')", ctx, "trace-4");
        assertTrue(true);
    }

    @Test
    @DisplayName("while 循环在编译期被拒（CPU 空转超时救不了）")
    void whileLoopIsRejected() {
        ScriptError error = assertThrows(ScriptError.class,
                () -> sandbox.execute("while (true) { }", newCtx(), "t"));

        assertEquals(ScriptError.Phase.COMPILE, error.getPhase());
    }

    @Test
    @DisplayName("非白名单类（java.io.File）无法实例化")
    void fileAccessIsRejected() {
        ScriptError error = assertThrows(ScriptError.class,
                () -> sandbox.execute("ctx.vars.x = new File('/etc/passwd').text", newCtx(), "t"));

        assertEquals(ScriptError.Phase.COMPILE, error.getPhase());
    }

    @Test
    @DisplayName("System.exit 被拒")
    void systemExitIsRejected() {
        ScriptError error = assertThrows(ScriptError.class,
                () -> sandbox.execute("System.exit(0)", newCtx(), "t"));

        assertEquals(ScriptError.Phase.COMPILE, error.getPhase());
    }

    @Test
    @DisplayName("反射逃逸（getClass / forName）被拒")
    void reflectionIsRejected() {
        ScriptError byGetClass = assertThrows(ScriptError.class,
                () -> sandbox.execute("ctx.vars.c = 'x'.getClass()", newCtx(), "t"));
        assertEquals(ScriptError.Phase.COMPILE, byGetClass.getPhase());

        ScriptError byForName = assertThrows(ScriptError.class,
                () -> sandbox.execute("ctx.vars.c = Class.forName('java.lang.Runtime')", newCtx(), "t"));
        assertEquals(ScriptError.Phase.COMPILE, byForName.getPhase());
    }

    @Test
    @DisplayName("超时被中断并抛 TIMEOUT，之后沙箱仍可用")
    void timeoutIsEnforced() {
        GroovySandbox shortTimeout = new GroovySandbox(
                new GroovySandboxOptions(true, 100L, 10, 60_000L), null);
        try {
            String slow = """
                    def sb = new StringBuilder()
                    for (int i = 0; i < 3000000; i++) { sb.append('x') }
                    ctx.vars.len = sb.length()
                    """;
            ScriptError error = assertThrows(ScriptError.class,
                    () -> shortTimeout.execute(slow, newCtx(), "t"));
            assertEquals(ScriptError.Phase.TIMEOUT, error.getPhase());
            assertTrue(error.getMessage().contains("超时"));

            Map<String, Object> ctx = newCtx();
            shortTimeout.execute("ctx.vars.ok = true", ctx, "t2");
            assertTrue(((Map<?, ?>) ctx.get("vars")).containsKey("ok"));
        } finally {
            shortTimeout.close();
        }
    }

    @Test
    @DisplayName("运行期异常映射为 RUNTIME 阶段，消息是人话")
    void runtimeFailureIsMapped() {
        ScriptError error = assertThrows(ScriptError.class,
                () -> sandbox.execute("ctx.vars.x = [1, 2].get(5)", newCtx(), "t"));

        assertEquals(ScriptError.Phase.RUNTIME, error.getPhase());
        assertTrue(error.getMessage().startsWith("脚本执行失败："));
        assertTrue(error.getScriptHash().length() == 12);
    }

    @Test
    @DisplayName("编译缓存：同一脚本只编译一次")
    void compiledScriptIsCached() {
        String script = "ctx.vars.n = 1";
        sandbox.execute(script, newCtx(), "t");
        sandbox.execute(script, newCtx(), "t");

        assertEquals(1, sandbox.cacheSize());
    }

    @Test
    @DisplayName("总开关关闭时跳过执行，上下文原样返回")
    @SuppressWarnings("unchecked")
    void disabledSandboxSkipsExecution() {
        GroovySandbox disabled = new GroovySandbox(
                new GroovySandboxOptions(false, 1000L, 10, 60_000L), null);
        Map<String, Object> ctx = newCtx();

        ScriptResult result = disabled.execute("ctx.vars.n = 1", ctx, "t");

        assertTrue(result.skipped());
        assertFalse(((Map<String, Object>) ctx.get("vars")).containsKey("n"));
        disabled.close();
    }

    @Test
    @DisplayName("空脚本不执行也不报错")
    void blankScriptIsNoop() {
        ScriptResult result = sandbox.execute("   ", newCtx(), "t");
        assertFalse(result.skipped());
        assertEquals(0, sandbox.cacheSize());
    }
}
