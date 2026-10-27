package com.carbon.framework.expression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 兜底核单测：只支持路径/字面量/白名单函数，运算类表达式必须显式失败而不是猜。
 *
 * @author carbon
 */
class SimpleExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new DefaultExpressionEvaluator(new SimpleExpressionEvaluator());

    private static Map<String, Object> vars() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("id", "A1");
        inner.put("tags", List.of("x", "y"));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("input", Map.of("order", inner));
        return vars;
    }

    @Test
    @DisplayName("路径取值：Map 与数组下标")
    void shouldResolvePath() {
        assertThat(evaluator.eval("input.order.id", vars())).isEqualTo("A1");
        assertThat(evaluator.eval("input.order.tags[0]", vars())).isEqualTo("x");
    }

    @Test
    @DisplayName("缺失路径返回 null")
    void shouldReturnNull() {
        assertThat(evaluator.eval("input.nope", vars())).isNull();
        assertThat(evaluator.eval("input.order.tags[9]", vars())).isNull();
    }

    @Test
    @DisplayName("字面量：字符串 / 整数 / 小数 / 布尔 / null")
    void shouldParseLiterals() {
        assertThat(evaluator.eval("'abc'", vars())).isEqualTo("abc");
        assertThat(evaluator.eval("42", vars())).isEqualTo(42L);
        assertThat(evaluator.eval("true", vars())).isEqualTo(Boolean.TRUE);
        assertThat(evaluator.eval("null", vars())).isNull();
        assertThat(evaluator.eval("1.5", vars())).hasToString("1.5");
    }

    @Test
    @DisplayName("白名单函数：嵌套参数与字符串参数")
    void shouldCallWhitelistedFunction() {
        assertThat(evaluator.eval("upper('ab')", vars())).isEqualTo("AB");
        assertThat(evaluator.eval("jsonpath('{\"a\":{\"b\":2}}', '$.a.b')", vars())).isEqualTo(2);
        assertThat(evaluator.eval("md5('abc')", vars()))
                .isEqualTo("900150983cd24fb0d6963f7d28e17f72");
    }

    @Test
    @DisplayName("?? 默认值：左侧 null 才取右侧")
    void shouldApplyDefault() {
        assertThat(evaluator.eval("input.nope ?? 'd'", vars())).isEqualTo("d");
        assertThat(evaluator.eval("input.order.id ?? 'd'", vars())).isEqualTo("A1");
    }

    @Test
    @DisplayName("算术不被支持 → 显式报错（兜底核不猜）")
    void shouldRejectArithmetic() {
        assertThatThrownBy(() -> evaluator.eval("1 + 2", vars()))
                .isInstanceOf(RenderException.class);
    }

    @Test
    @DisplayName("白名单外函数 → PARAM_INVALID")
    void shouldRejectUnknownFunction() {
        assertThatThrownBy(() -> evaluator.eval("rmrf('/')", vars()))
                .isInstanceOf(RenderException.class)
                .hasMessageContaining("未在白名单中的函数");
    }

    @Test
    @DisplayName("条件求值：非 boolean → PARAM_INVALID")
    void testShouldRejectNonBoolean() {
        assertThatThrownBy(() -> evaluator.test("input.order.id", vars()))
                .isInstanceOf(RenderException.class)
                .hasMessageContaining("必须返回 boolean");
    }
}
