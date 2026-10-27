package com.carbon.framework.expression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 兜底求值核：<b>仅</b>路径取值 + 字面量 + 白名单函数调用。
 *
 * <p>存在的唯一理由是 Aviator 未通过 6 条逃逸门禁时的退路（§4.2「Aviator 准入条件」），
 * 因此刻意<b>不支持</b>算术、比较、三元等运算——宁可退化，也不要自己写一个表达式解释器。
 * {@code ??} 默认值由 {@link DefaultExpressionEvaluator} 在外层统一处理，本类不感知。
 *
 * @author carbon
 */
public class SimpleExpressionEvaluator implements ExpressionEvaluator {

    @Override
    public Object eval(String expr, Map<String, Object> vars) {
        if (expr == null || expr.isBlank()) {
            throw RenderException.paramInvalid("表达式为空");
        }
        return new Parser(expr, vars).parseWhole();
    }

    @Override
    public boolean test(String expr, Map<String, Object> vars) {
        Object value = eval(expr, vars);
        if (value instanceof Boolean b) {
            return b;
        }
        throw RenderException.paramInvalid("条件表达式必须返回 boolean，实际为 "
                + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    @Override
    public String name() {
        return "simple";
    }

    /** 极简递归下降解析器。 */
    private static final class Parser {

        private final String text;
        private final Map<String, Object> vars;
        private int pos;

        private Parser(String text, Map<String, Object> vars) {
            this.text = text;
            this.vars = vars;
        }

        private Object parseWhole() {
            Object value = parseArg();
            skipSpaces();
            if (pos < text.length()) {
                throw RenderException.paramInvalid("不支持的表达式语法（兜底核仅支持路径/字面量/函数）: " + text);
            }
            return value;
        }

        private Object parseArg() {
            skipSpaces();
            if (pos >= text.length()) {
                throw RenderException.paramInvalid("表达式意外结束: " + text);
            }
            char c = text.charAt(pos);
            if (c == '\'' || c == '"') {
                return parseQuoted(c);
            }
            if (Character.isDigit(c) || (c == '-' && pos + 1 < text.length()
                    && Character.isDigit(text.charAt(pos + 1)))) {
                return parseNumber();
            }
            String ident = parseIdent();
            switch (ident) {
                case "true" -> {
                    return Boolean.TRUE;
                }
                case "false" -> {
                    return Boolean.FALSE;
                }
                case "null" -> {
                    return null;
                }
                default -> {
                    skipSpaces();
                    if (pos < text.length() && text.charAt(pos) == '(') {
                        return parseCall(ident);
                    }
                    return resolve(ident);
                }
            }
        }

        private Object parseCall(String name) {
            pos++;
            List<Object> args = new ArrayList<>(2);
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == ')') {
                pos++;
                return BuiltinFunctions.call(name, args);
            }
            while (true) {
                args.add(parseArg());
                skipSpaces();
                if (pos >= text.length()) {
                    throw RenderException.paramInvalid("函数 " + name + "() 参数未闭合: " + text);
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ')') {
                    pos++;
                    return BuiltinFunctions.call(name, args);
                }
                throw RenderException.paramInvalid("函数 " + name + "() 参数分隔符异常: " + text);
            }
        }

        private String parseQuoted(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '\\' && pos < text.length()) {
                    char next = text.charAt(pos++);
                    sb.append(switch (next) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> next;
                    });
                    continue;
                }
                if (c == quote) {
                    return sb.toString();
                }
                sb.append(c);
            }
            throw RenderException.paramInvalid("字符串字面量未闭合: " + text);
        }

        private Object parseNumber() {
            int start = pos;
            if (text.charAt(pos) == '-') {
                pos++;
            }
            boolean dot = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    pos++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, pos);
            try {
                return dot ? new BigDecimal(raw) : Long.valueOf(raw);
            } catch (NumberFormatException e) {
                throw RenderException.paramInvalid("非法数字字面量: " + raw);
            }
        }

        private String parseIdent() {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                    pos++;
                } else if (c == '[') {
                    int close = text.indexOf(']', pos);
                    if (close < 0) {
                        throw RenderException.paramInvalid("路径下标未闭合: " + text);
                    }
                    pos = close + 1;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw RenderException.paramInvalid("无法解析的字符 '" + text.charAt(pos) + "': " + text);
            }
            return text.substring(start, pos);
        }

        private Object resolve(String path) {
            return PathReader.read(vars, path);
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
