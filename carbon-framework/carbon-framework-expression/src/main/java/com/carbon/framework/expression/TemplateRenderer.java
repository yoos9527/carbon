package com.carbon.framework.expression;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板渲染壳：{@code {{...}}} 的扫描、切分、类型判定与输出装配（§4.2 / §4.2.1）。
 *
 * <p>本类<b>不做任何计算</b>——连 {@code 1+1} 都不会算，全部交给 {@link ExpressionEvaluator}。
 * 它只负责四件事：扫占位符、判类型、拼输出、报错定位。
 *
 * <p>无状态纯函数（唯一的可变状态是扫描缓存，按模板原文做 LRU），可单测。
 *
 * @author carbon
 */
public class TemplateRenderer {

    /** 单条模板的扫描缓存上限。 */
    private static final int DEFAULT_CACHE_SIZE = 2000;

    private final ObjectMapper objectMapper;

    private final ExpressionEvaluator evaluator;

    private final Map<String, TemplateSegment.ScanResult> cache;

    /**
     * 构造。
     *
     * @param objectMapper JSON 序列化器
     * @param evaluator    表达式求值器
     */
    public TemplateRenderer(ObjectMapper objectMapper, ExpressionEvaluator evaluator) {
        this(objectMapper, evaluator, DEFAULT_CACHE_SIZE);
    }

    /**
     * 构造。
     *
     * @param objectMapper JSON 序列化器
     * @param evaluator    表达式求值器
     * @param cacheSize    扫描缓存条数
     */
    public TemplateRenderer(ObjectMapper objectMapper, ExpressionEvaluator evaluator, int cacheSize) {
        this.objectMapper = objectMapper;
        this.evaluator = evaluator;
        final int max = Math.max(1, cacheSize);
        this.cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TemplateSegment.ScanResult> eldest) {
                return size() > max;
            }
        };
    }

    /**
     * 扫描模板（结果带缓存，规则 8）。
     *
     * @param template 模板原文
     * @return 扫描结果
     */
    public TemplateSegment.ScanResult scan(String template) {
        if (template == null) {
            return new TemplateSegment.ScanResult(List.of());
        }
        synchronized (cache) {
            TemplateSegment.ScanResult cached = cache.get(template);
            if (cached != null) {
                return cached;
            }
        }
        TemplateSegment.ScanResult scanned = TemplateScanner.scan(template);
        synchronized (cache) {
            cache.put(template, scanned);
        }
        return scanned;
    }

    /**
     * 枚举模板中引用的变量（表单元数据复用，§4.2）。
     *
     * @param template 模板原文
     * @return 变量引用列表（按出现顺序，可重复）
     */
    public List<VarRef> varRefs(String template) {
        List<VarRef> refs = new ArrayList<>();
        for (TemplateSegment segment : scan(template).segments()) {
            if (segment instanceof TemplateSegment.Placeholder p) {
                refs.add(toVarRef(p));
            }
        }
        return refs;
    }

    /**
     * 渲染为文本：占位符结果一律转字符串后拼接（规则 2）。
     *
     * @param template 模板原文
     * @param context  渲染上下文
     * @return 渲染结果；模板为 {@code null} 时返回 {@code null}
     */
    public String renderText(String template, RenderContext context) {
        if (template == null) {
            return null;
        }
        TemplateSegment.ScanResult scanned = scan(template);
        if (!scanned.hasPlaceholder()) {
            return template;
        }
        StringBuilder sb = new StringBuilder(template.length() + 32);
        for (TemplateSegment segment : scanned.segments()) {
            if (segment instanceof TemplateSegment.Literal literal) {
                sb.append(literal.text());
            } else if (segment instanceof TemplateSegment.Placeholder p) {
                sb.append(asString(evalPlaceholder(p, context)));
            }
        }
        return sb.toString();
    }

    /**
     * 渲染 URL 片段并做 RFC3986 编码（规则 4）。
     *
     * @param template 模板原文
     * @param context  渲染上下文
     * @return 已编码的片段；模板为 {@code null} 时返回 {@code null}
     */
    public String renderUrlComponent(String template, RenderContext context) {
        String rendered = renderText(template, context);
        return rendered == null ? null : BuiltinFunctions.urlEncode(rendered);
    }

    /**
     * 渲染 JSON 模板：先归一化 → 解析为 JSON 树 → 逐叶子替换，转义由 Jackson 保证（规则 1/3）。
     *
     * @param jsonTemplate JSON 模板原文
     * @param context      渲染上下文
     * @return 渲染后的 JSON 文本
     */
    public String renderJson(String jsonTemplate, RenderContext context) {
        if (jsonTemplate == null || jsonTemplate.isBlank()) {
            return jsonTemplate;
        }
        JsonNode rendered = renderNode(parse(normalize(jsonTemplate)), context);
        try {
            return objectMapper.writeValueAsString(rendered);
        } catch (JsonProcessingException e) {
            throw new RenderException(RenderException.PARAM_INVALID, "渲染结果序列化失败: " + e.getOriginalMessage(),
                    null, null, e);
        }
    }

    /**
     * 渲染 JSON 树（规则 1/3）：叶子字符串逐个渲染，纯占位符叶子做类型透传。
     *
     * @param node    JSON 节点
     * @param context 渲染上下文
     * @return 新节点（不修改入参）
     */
    public JsonNode renderNode(JsonNode node, RenderContext context) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return renderTextNode((TextNode) node, context);
        }
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                copy.set(entry.getKey(), renderNode(entry.getValue(), context));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                copy.add(renderNode(child, context));
            }
            return copy;
        }
        return node;
    }

    /**
     * 批量渲染字符串 Map（header / query），保持插入顺序。
     *
     * @param map     模板 Map
     * @param context 渲染上下文
     * @param urlSafe 是否做 RFC3986 编码
     * @return 新 Map
     */
    public Map<String, String> renderMap(Map<String, String> map, RenderContext context, boolean urlSafe) {
        Map<String, String> rendered = new LinkedHashMap<>();
        if (map == null) {
            return rendered;
        }
        map.forEach((key, value) -> rendered.put(key, urlSafe
                ? renderUrlComponent(value, context)
                : renderText(value, context)));
        return rendered;
    }

    // ---------------------------------------------------------------- internals

    private JsonNode renderTextNode(TextNode node, RenderContext context) {
        String text = node.textValue();
        TemplateSegment.ScanResult scanned = scan(text);
        if (!scanned.hasPlaceholder()) {
            return node;
        }
        // 规则 1：占位符独占整个值 → 类型透传
        if (scanned.isSinglePlaceholder()) {
            Object value = evalPlaceholder(scanned.single(), context);
            return objectMapper.valueToTree(value);
        }
        // 规则 2：嵌在字符串中 → 逐占位符转字符串拼接
        StringBuilder sb = new StringBuilder(text.length() + 32);
        for (TemplateSegment segment : scanned.segments()) {
            if (segment instanceof TemplateSegment.Literal literal) {
                sb.append(literal.text());
            } else if (segment instanceof TemplateSegment.Placeholder p) {
                sb.append(asString(evalPlaceholder(p, context)));
            }
        }
        return TextNode.valueOf(sb.toString());
    }

    /**
     * 求值单个占位符；结果为空时按规则 5 抛错（不静默渲染为空串）。
     */
    private Object evalPlaceholder(TemplateSegment.Placeholder placeholder, RenderContext context) {
        Object value;
        try {
            value = evaluator.eval(placeholder.expr(), context.toVars());
        } catch (RenderException e) {
            throw e;
        } catch (Exception e) {
            throw new RenderException(RenderException.EXPRESSION_ERROR,
                    "表达式求值失败: " + e.getMessage(), varPathOf(placeholder.expr()),
                    placeholder.start(), e);
        }
        if (value == null) {
            throw RenderException.paramInvalid(
                    "变量缺失或求值为空，且未配置 ?? 默认值", varPathOf(placeholder.expr()), placeholder.start());
        }
        return value;
    }

    private Object asString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?> || value instanceof Object[]) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new RenderException(RenderException.EXPRESSION_ERROR,
                        "复合类型无法转字符串: " + e.getOriginalMessage(), null, null, e);
            }
        }
        return String.valueOf(value);
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RenderException(RenderException.PARAM_INVALID,
                    "模板不是合法 JSON（渲染前必须先解析为 JSON 树，规则 3）: " + e.getOriginalMessage(),
                    null, null, e);
        }
    }

    /**
     * JSON 语义归一化：把「JSON 字符串外的裸占位符」补上引号，使其能先被解析成 JSON 树。
     *
     * <p>为什么必需：{@code {"page":{{input.page}}} 与 {@code {"d":{{vars.obj}}}}
     * 本身不是合法 JSON（规则 1 的写法），不归一化就无法“先解析成树”。补引号后它们变成
     * 单占位符叶子文本节点，再由 {@link #renderTextNode} 做类型透传。
     *
     * <p>已经在 JSON 字符串内的占位符（{@code "A-{{x}}-B"}）原样保留，交给逐叶子渲染。
     *
     * @param template JSON 模板
     * @return 可解析的 JSON 文本
     */
    private String normalize(String template) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        boolean inString = false;
        boolean escape = false;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (escape) {
                out.append(c);
                escape = false;
                i++;
                continue;
            }
            if (c == '\\') {
                out.append(c);
                escape = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                i++;
                continue;
            }
            if (!inString && c == '{' && i + 1 < template.length() && template.charAt(i + 1) == '{') {
                int close = TemplateScanner.findClose(template, i + 2);
                if (close < 0) {
                    throw RenderException.paramInvalid("模板占位符未闭合（位置 " + i + "）");
                }
                String raw = template.substring(i, close + 2);
                try {
                    out.append(objectMapper.writeValueAsString(raw));
                } catch (JsonProcessingException e) {
                    throw new RenderException(RenderException.PARAM_INVALID,
                            "占位符归一化失败: " + e.getOriginalMessage(), null, i, e);
                }
                i = close + 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static VarRef toVarRef(TemplateSegment.Placeholder placeholder) {
        List<String> parts = DefaultExpressionEvaluator.splitDefault(placeholder.expr());
        boolean hasDefault = parts.size() > 1;
        String head = parts.get(0).trim();
        String scope = scopeOf(head);
        return new VarRef(scope, pathOf(head), hasDefault, placeholder.raw());
    }

    private static String scopeOf(String expr) {
        int dot = expr.indexOf('.');
        int paren = expr.indexOf('(');
        if (paren >= 0 && (dot < 0 || paren < dot)) {
            return "function";
        }
        String head = dot < 0 ? expr : expr.substring(0, dot);
        return switch (head) {
            case "input", "vars", "auth", "env" -> head;
            default -> "unknown";
        };
    }

    private static String pathOf(String expr) {
        int paren = expr.indexOf('(');
        int dot = expr.indexOf('.');
        if (paren >= 0 && (dot < 0 || paren < dot)) {
            return expr.substring(0, paren);
        }
        return expr;
    }

    private static String varPathOf(String expr) {
        List<String> parts = DefaultExpressionEvaluator.splitDefault(expr);
        return parts.isEmpty() ? expr : pathOf(parts.get(0).trim());
    }
}
