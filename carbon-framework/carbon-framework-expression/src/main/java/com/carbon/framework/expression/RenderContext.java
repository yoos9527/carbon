package com.carbon.framework.expression;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 渲染上下文：渲染器与求值器之间唯一的通信载体。
 *
 * <p>作用域优先级（高 → 低）：{@code input} > {@code vars} > {@code auth} > {@code env} > 内置常量（§4.2）。
 * 三层各自独立替换时，只依赖本对象的 Map 形状，不共享其它对象。
 *
 * @author carbon
 */
public final class RenderContext {

    /** 调用方入参（优先级最高）。 */
    private final Map<String, Object> input = new LinkedHashMap<>();

    /** 流程/脚本产生的中间变量。 */
    private final Map<String, Object> vars = new LinkedHashMap<>();

    /** 鉴权注入的字段（如 token）。 */
    private final Map<String, Object> auth = new LinkedHashMap<>();

    /** 环境变量（如 base_url_prefix、密钥）。 */
    private final Map<String, Object> env = new LinkedHashMap<>();

    /**
     * 空上下文。
     *
     * @return 新实例
     */
    public static RenderContext create() {
        return new RenderContext();
    }

    /**
     * 合并成求值器可见的变量池。
     *
     * <p>四个作用域本身是 Map，可直接用 {@code input.x} / {@code env.host} 访问。
     *
     * @return 变量池（新 Map，调用方可安全读取）
     */
    public Map<String, Object> toVars() {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("env", env);
        merged.put("auth", auth);
        merged.put("vars", vars);
        merged.put("input", input);
        return merged;
    }

    public Map<String, Object> input() {
        return input;
    }

    public Map<String, Object> vars() {
        return vars;
    }

    public Map<String, Object> auth() {
        return auth;
    }

    public Map<String, Object> env() {
        return env;
    }

    /**
     * 放入入参。
     *
     * @param key   键
     * @param value 值
     * @return this
     */
    public RenderContext input(String key, Object value) {
        input.put(key, value);
        return this;
    }

    /**
     * 放入中间变量。
     *
     * @param key   键
     * @param value 值
     * @return this
     */
    public RenderContext var(String key, Object value) {
        vars.put(key, value);
        return this;
    }

    /**
     * 放入鉴权字段。
     *
     * @param key   键
     * @param value 值
     * @return this
     */
    public RenderContext auth(String key, Object value) {
        auth.put(key, value);
        return this;
    }

    /**
     * 放入环境变量。
     *
     * @param key   键
     * @param value 值
     * @return this
     */
    public RenderContext env(String key, Object value) {
        env.put(key, value);
        return this;
    }
}
