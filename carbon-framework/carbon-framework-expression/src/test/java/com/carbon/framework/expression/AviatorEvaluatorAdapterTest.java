package com.carbon.framework.expression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Aviator 求值核单测：<b>包含 §4.2 规定的 6 条逃逸门禁</b>，任何一条不通过都必须回退到
 * {@link SimpleExpressionEvaluator}。
 *
 * @author carbon
 */
class AviatorEvaluatorAdapterTest {

    private final ExpressionEvaluator evaluator = new DefaultExpressionEvaluator(new AviatorEvaluatorAdapter());

    private static Map<String, Object> vars() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", "A1");
        order.put("amount", 100);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("input", Map.of("order", order, "page", 2, "name", "carbon"));
        vars.put("env", Map.of("host", "api.demo.com"));
        return vars;
    }

    // ------------------------------------------------------------ 逃逸门禁 6 条

    @ParameterizedTest(name = "逃逸用例必须失败: {0}")
    @ValueSource(strings = {
            "new File('/etc/passwd')",
            "Runtime.getRuntime().exec('id')",
            "System.exit(0)",
            "Class.forName('java.lang.Runtime')",
            "while(true){}",
            "Thread.sleep(99999)"
    })
    @DisplayName("门禁：反射/进程/类加载/死循环/线程挂起一律不可用")
    void escapeAttemptsMustFail(String expression) {
        assertThatThrownBy(() -> evaluator.eval(expression, vars()))
                .as("表达式不应被允许: %s", expression)
                .isInstanceOf(RenderException.class);
    }

    @Test
    @DisplayName("门禁：未注册函数（含反射后门）必须失败")
    void unknownFunctionMustFail() {
        assertThatThrownBy(() -> evaluator.eval("unknownFn('x')", vars()))
                .isInstanceOf(RenderException.class);
    }

    // ------------------------------------------------------------ 正常能力

    @Test
    @DisplayName("四则运算与比较")
    void shouldEvaluateArithmeticAndComparison() {
        assertThat(evaluator.eval("1 + 2 * 3", vars())).isEqualTo(7L);
        assertThat(evaluator.eval("input.page > 1", vars())).isEqualTo(Boolean.TRUE);
        assertThat(evaluator.test("input.order.amount >= 100", vars())).isTrue();
    }

    @Test
    @DisplayName("路径取值：Map 嵌套与数组下标")
    void shouldResolvePath() {
        assertThat(evaluator.eval("input.order.id", vars())).isEqualTo("A1");

        Map<String, Object> withList = Map.of("l", List.of(10, 20));
        assertThat(evaluator.eval("l[1]", withList)).isEqualTo(20);
    }

    @Test
    @DisplayName("缺失路径返回 null（供 ?? 默认值接住）")
    void shouldReturnNullForMissingPath() {
        assertThat(evaluator.eval("input.nope", vars())).isNull();
    }

    @Test
    @DisplayName("?? 默认值：左侧为 null 才取右侧，且支持链式")
    void shouldApplyDefaultValue() {
        assertThat(evaluator.eval("input.nope ?? 1", vars())).isEqualTo(1L);
        assertThat(evaluator.eval("input.page ?? 9", vars())).isEqualTo(2);
        assertThat(evaluator.eval("input.a ?? input.b ?? 'fallback'", vars())).isEqualTo("fallback");
    }

    @Test
    @DisplayName("条件表达式非 boolean → PARAM_INVALID")
    void testShouldRejectNonBoolean() {
        assertThatThrownBy(() -> evaluator.test("input.page", vars()))
                .isInstanceOf(RenderException.class)
                .hasMessageContaining("必须返回 boolean");
    }

    @Test
    @DisplayName("白名单函数可用，且与兜底核结果一致（只比确定性函数）")
    void shouldRunWhitelistedFunctions() {
        ExpressionEvaluator simple = new DefaultExpressionEvaluator(new SimpleExpressionEvaluator());
        for (String expr : List.of("md5('abc')", "sha256('abc')", "base64('hi')", "base64Decode('aGk=')",
                "upper('ab')", "lower('AB')", "trim(' a ')", "replace('a-b', '-', '_')",
                "urlEncode('a b')", "hmacSha256('k', 'd')", "jsonpath('{\"a\":1}', '$.a')")) {
            assertThat(evaluator.eval(expr, vars())).as(expr).isEqualTo(simple.eval(expr, vars()));
        }
    }

    @Test
    @DisplayName("jsonpath 禁止深层扫描 ..")
    void jsonpathShouldRejectDeepScan() {
        assertThatThrownBy(() -> evaluator.eval("jsonpath('{\"a\":1}', '$..a')", vars()))
                .isInstanceOf(RenderException.class)
                .hasMessageContaining("禁止深层扫描");
    }

    @Test
    @DisplayName("数值默认值保持整数语义（不变成科学计数/字符串）")
    void defaultNumberShouldStayInteger() {
        Object value = evaluator.eval("input.nope ?? 1", vars());

        assertThat(value).isInstanceOf(Number.class);
        assertThat(((Number) value).intValue()).isEqualTo(1);
        assertThat(value).isNotInstanceOf(BigDecimal.class);
    }
}
