package com.carbon.framework.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认值语法的统一入口：把 {@code a ?? b ?? c} 拆成按序求值的候选链。
 *
 * <p>把 {@code ??} 收在这一层处理，而不是让它渗进每个求值器：
 * 这样 Aviator 与自研兜底核对 {@code ??} 的语义完全一致（左侧求值为 null 才取右侧）。
 *
 * @author carbon
 */
public final class DefaultExpressionEvaluator implements ExpressionEvaluator {

    private final ExpressionEvaluator delegate;

    public DefaultExpressionEvaluator(ExpressionEvaluator delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object eval(String expr, Map<String, Object> vars) {
        List<String> parts = splitDefault(expr);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Object value = delegate.eval(trimmed, vars);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Override
    public boolean test(String expr, Map<String, Object> vars) {
        return delegate.test(expr, vars);
    }

    @Override
    public String name() {
        return "default(" + delegate.name() + ")";
    }

    /**
     * 在「不在引号内、不在括号内」的 {@code ??} 处切分。
     *
     * @param expr 表达式
     * @return 候选片段（至少一段）
     */
    static List<String> splitDefault(String expr) {
        List<String> parts = new ArrayList<>(2);
        int depth = 0;
        char quote = 0;
        boolean escape = false;
        int start = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            switch (c) {
                case '(', '[', '{' -> depth++;
                case ')', ']', '}' -> depth--;
                default -> {
                    if (c == '?' && i + 1 < expr.length() && expr.charAt(i + 1) == '?' && depth == 0) {
                        parts.add(expr.substring(start, i));
                        start = i + 2;
                        i++;
                    }
                }
            }
        }
        parts.add(expr.substring(start));
        return parts;
    }
}
