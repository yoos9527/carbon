package com.carbon.framework.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渲染壳单测：逐条覆盖 §4.2.1 的 8 条规则。
 *
 * @author carbon
 */
class TemplateRendererTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TemplateRenderer(objectMapper,
                new DefaultExpressionEvaluator(new AviatorEvaluatorAdapter()));
    }

    @Test
    @DisplayName("规则 1：占位符独占整个值 → 类型透传（数字仍是数字）")
    void shouldPassThroughTypeWhenPlaceholderIsWholeValue() throws Exception {
        RenderContext ctx = RenderContext.create().input("page", 3);

        String json = renderer.renderJson("{\"page\":{{input.page}}}", ctx);

        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("page").isNumber()).isTrue();
        assertThat(node.get("page").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("规则 1：默认值生效且保持数字类型")
    void shouldUseDefaultValueAndKeepNumberType() throws Exception {
        String json = renderer.renderJson("{\"page\":{{input.page ?? 1}}}", RenderContext.create());

        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("page").isNumber()).isTrue();
        assertThat(node.get("page").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("规则 1：Map/List 直接塞入 JSON 树")
    void shouldInsertCompositeValue() throws Exception {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("k", "v");
        RenderContext ctx = RenderContext.create().var("obj", obj).var("list", List.of(1, 2));

        JsonNode node = objectMapper.readTree(
                renderer.renderJson("{\"d\":{{vars.obj}},\"l\":{{vars.list}}}", ctx));

        assertThat(node.get("d").get("k").asText()).isEqualTo("v");
        assertThat(node.get("l")).hasSize(2);
    }

    @Test
    @DisplayName("规则 2：嵌在字符串中 → 转字符串拼接")
    void shouldInterpolateInsideString() throws Exception {
        RenderContext ctx = RenderContext.create().input("id", 10).input("page", 2);

        JsonNode node = objectMapper.readTree(
                renderer.renderJson("{\"m\":\"A-{{input.id}}-B/{{input.page}}\"}", ctx));

        assertThat(node.get("m").asText()).isEqualTo("A-10-B/2");
    }

    @Test
    @DisplayName("规则 3：JSON 转义由 Jackson 保证，引号不会破坏报文")
    void shouldEscapeQuotesSafely() throws Exception {
        RenderContext ctx = RenderContext.create().input("name", "he said \"hi\"\\ and \n newline");

        String json = renderer.renderJson("{\"name\":\"{{input.name}}\"}", ctx);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("name").asText()).isEqualTo("he said \"hi\"\\ and \n newline");
    }

    @Test
    @DisplayName("规则 4：URL 片段自动 RFC3986 编码（空格→%20，不出现 +）")
    void shouldEncodeUrlComponent() {
        RenderContext ctx = RenderContext.create().input("kw", "a b&c=d");

        String encoded = renderer.renderUrlComponent("{{input.kw}}", ctx);

        assertThat(encoded).isEqualTo("a%20b%26c%3Dd");
        assertThat(encoded).doesNotContain("+");
    }

    @Test
    @DisplayName("规则 5：缺变量且无默认值 → PARAM_INVALID，带变量路径与位置")
    void shouldFailOnMissingVariable() {
        assertThatThrownBy(() -> renderer.renderText("A-{{input.nope}}-B", RenderContext.create()))
                .isInstanceOf(RenderException.class)
                .satisfies(e -> {
                    RenderException ex = (RenderException) e;
                    assertThat(ex.getCode()).isEqualTo(RenderException.PARAM_INVALID);
                    assertThat(ex.getVarPath()).isEqualTo("input.nope");
                    assertThat(ex.getPosition()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("规则 5：缺失变量不得静默渲染为空串")
    void shouldNeverRenderMissingAsEmptyString() {
        assertThatThrownBy(() -> renderer.renderText("{{vars.token}}", RenderContext.create()))
                .isInstanceOf(RenderException.class)
                .hasMessageContaining("变量缺失");
    }

    @Test
    @DisplayName("规则 8：同一模板重复扫描命中缓存（同一实例）")
    void shouldCacheScanResult() {
        TemplateSegment.ScanResult first = renderer.scan("A-{{input.x}}-B");
        TemplateSegment.ScanResult second = renderer.scan("A-{{input.x}}-B");

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("表单元数据：varRefs 给出作用域、路径与是否带默认值")
    void shouldExposeVarRefs() {
        List<VarRef> refs = renderer.varRefs("{{input.a}}-{{vars.b ?? 'x'}}-{{now()}}-{{auth.token}}");

        assertThat(refs).hasSize(4);
        assertThat(refs.get(0)).isEqualTo(new VarRef("input", "input.a", false, "{{input.a}}"));
        assertThat(refs.get(1).hasDefault()).isTrue();
        assertThat(refs.get(1).scope()).isEqualTo("vars");
        assertThat(refs.get(2).scope()).isEqualTo("function");
        assertThat(refs.get(2).path()).isEqualTo("now");
        assertThat(refs.get(3).scope()).isEqualTo("auth");
    }

    @Test
    @DisplayName("白名单函数参与渲染（hmacSha256 出 64 位十六进制）")
    void shouldRenderWhitelistedFunction() {
        RenderContext ctx = RenderContext.create().env("secret", "k");

        String rendered = renderer.renderText("{{hmacSha256(env.secret, 'data')}}", ctx);

        assertThat(rendered).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("路径取值：嵌套 Map 与数组下标")
    void shouldResolveNestedPathAndIndex() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", "A1");
        order.put("tags", List.of("x", "y"));
        RenderContext ctx = RenderContext.create()
                .input("order", order);

        String rendered = renderer.renderText("{{input.order.id}}/{{input.order.tags[1]}}", ctx);

        assertThat(rendered).isEqualTo("A1/y");
    }

    @Test
    @DisplayName("header/query 批量渲染：urlSafe 决定是否编码")
    void shouldRenderMapWithOptionalEncoding() {
        RenderContext ctx = RenderContext.create().input("kw", "a b");
        Map<String, String> map = new LinkedHashMap<>();
        map.put("X-Trace", "{{input.kw}}");
        map.put("q", "{{input.kw}}");

        Map<String, String> plain = renderer.renderMap(map, ctx, false);
        Map<String, String> encoded = renderer.renderMap(map, ctx, true);

        assertThat(plain.get("q")).isEqualTo("a b");
        assertThat(encoded.get("q")).isEqualTo("a%20b");
    }

    @Test
    @DisplayName("渲染后的 body 必须能被 Jackson 再次解析（渲染后置校验）")
    void renderedBodyShouldBeParsable() throws Exception {
        RenderContext ctx = RenderContext.create().input("s", "a\"b");

        String json = renderer.renderJson("{\"k\":\"{{input.s}}\"}", ctx);

        assertThat(objectMapper.readTree(json).get("k").asText()).isEqualTo("a\"b");
    }

    @Test
    @DisplayName("非法 JSON 模板 → PARAM_INVALID（规则 3 要求先解析成树）")
    void shouldRejectNonJsonTemplate() {
        assertThatThrownBy(() -> renderer.renderJson("{k:1}", RenderContext.create()))
                .isInstanceOf(RenderException.class)
                .hasMessageContaining("模板不是合法 JSON");
    }
}
