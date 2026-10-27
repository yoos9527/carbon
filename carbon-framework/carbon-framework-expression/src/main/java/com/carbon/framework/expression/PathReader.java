package com.carbon.framework.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 路径取值器：{@code input.order.tags[0].name} → Java 值。
 *
 * <p>壳（模板）与两个求值核共用同一份取值语义，保证「Aviator 模式」与「兜底核模式」切换时
 * 路径解析行为零差异。缺失路径一律返回 {@code null}（由外层的 {@code ??} 默认值接住），
 * <b>不抛异常</b>——「变量缺失」的统一报错点在 {@link TemplateRenderer}。
 *
 * @author carbon
 */
public final class PathReader {

    private PathReader() {
    }

    /**
     * 按路径取值。
     *
     * @param root 根对象（通常是变量池 Map）
     * @param path 路径，支持 {@code a.b} 与 {@code a[0].b}
     * @return 值；任一段缺失返回 {@code null}
     */
    public static Object read(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        for (String token : tokenize(path)) {
            current = step(current, token, path);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * 切分路径。
     *
     * @param path 路径
     * @return 段列表
     */
    static String[] tokenize(String path) {
        List<String> tokens = new ArrayList<>(4);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                flush(tokens, current);
            } else if (c == '[') {
                flush(tokens, current);
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw RenderException.paramInvalid("路径下标未闭合: " + path);
                }
                tokens.add(path.substring(i + 1, close).trim());
                i = close;
            } else {
                current.append(c);
            }
        }
        flush(tokens, current);
        return tokens.toArray(new String[0]);
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object step(Object current, String token, String fullPath) {
        if (current instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map).get(token);
        }
        if (current instanceof List<?> list) {
            int index = toIndex(token, fullPath);
            return index < list.size() ? list.get(index) : null;
        }
        if (current.getClass().isArray()) {
            Object[] array = (Object[]) current;
            int index = toIndex(token, fullPath);
            return index < array.length ? array[index] : null;
        }
        throw RenderException.paramInvalid("路径 " + fullPath + " 无法继续下钻："
                + current.getClass().getSimpleName() + " 不支持 '" + token + "'");
    }

    private static int toIndex(String token, String fullPath) {
        try {
            int index = Integer.parseInt(token);
            if (index < 0) {
                throw RenderException.paramInvalid("路径 " + fullPath + " 的下标不能为负: " + index);
            }
            return index;
        } catch (NumberFormatException e) {
            throw RenderException.paramInvalid("路径 " + fullPath + " 的 '" + token + "' 不是合法下标");
        }
    }
}
