package com.carbon.framework.expression;

import java.util.ArrayList;
import java.util.List;

/**
 * 引号感知的模板扫描器（规则 6）。
 *
 * <p>禁止用正则 {@code \{\{(.*?)\}\}} 找占位符，因为它会把
 * {@code {{jsonpath(a, '$.b}')}}} 在第一个 {@code }} 处截断。本扫描器在引号内的 {@code }} 不认。
 *
 * @author carbon
 */
public final class TemplateScanner {

    private static final char OPEN_1 = '{';
    private static final char OPEN_2 = '{';
    private static final char CLOSE_1 = '}';
    private static final char CLOSE_2 = '}';

    private TemplateScanner() {
    }

    /**
     * 扫描模板。
     *
     * @param template 模板原文
     * @return 扫描结果（字面量与占位符交替）
     * @throws RenderException 花括号不配对时
     */
    public static TemplateSegment.ScanResult scan(String template) {
        List<TemplateSegment> segments = new ArrayList<>();
        if (template == null || template.isEmpty()) {
            return new TemplateSegment.ScanResult(segments);
        }
        int length = template.length();
        int cursor = 0;
        StringBuilder literal = new StringBuilder();
        while (cursor < length) {
            if (isOpen(template, cursor)) {
                int close = findClose(template, cursor + 2);
                if (close < 0) {
                    throw new RenderException(RenderException.PARAM_INVALID,
                            "模板占位符未闭合（位置 " + cursor + "）: " + excerpt(template, cursor),
                            extractVarPath(template, cursor + 2, length), cursor, null);
                }
                if (!literal.isEmpty()) {
                    segments.add(new TemplateSegment.Literal(literal.toString()));
                    literal.setLength(0);
                }
                String expr = template.substring(cursor + 2, close).trim();
                if (expr.isEmpty()) {
                    throw new RenderException(RenderException.PARAM_INVALID,
                            "空占位符（位置 " + cursor + "）", null, cursor, null);
                }
                segments.add(new TemplateSegment.Placeholder(expr, cursor, close + 2,
                        template.substring(cursor, close + 2)));
                cursor = close + 2;
            } else {
                literal.append(template.charAt(cursor));
                cursor++;
            }
        }
        if (!literal.isEmpty()) {
            segments.add(new TemplateSegment.Literal(literal.toString()));
        }
        return new TemplateSegment.ScanResult(segments);
    }

    private static boolean isOpen(String text, int index) {
        return index + 1 < text.length()
                && text.charAt(index) == OPEN_1
                && text.charAt(index + 1) == OPEN_2;
    }

    /**
     * 从 {@code from} 起找配对的 {@code }}，跳过单/双引号内的内容。
     *
     * <p>包级可见：JSON 模板归一化（裸占位符补引号）需要复用同一套引号感知逻辑。
     *
     * @return {@code }} 中第一个 {@code }} 的下标，未找到返回 -1
     */
    static int findClose(String text, int from) {
        char quote = 0;
        boolean escape = false;
        for (int i = from; i < text.length() - 1; i++) {
            char c = text.charAt(i);
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
            if (c == CLOSE_1 && text.charAt(i + 1) == CLOSE_2) {
                return i;
            }
        }
        return -1;
    }

    private static String excerpt(String template, int from) {
        int to = Math.min(template.length(), from + 32);
        return template.substring(from, to);
    }

    private static String extractVarPath(String template, int from, int to) {
        int end = Math.min(to, from + 64);
        String fragment = template.substring(from, end);
        int stop = fragment.length();
        for (int i = 0; i < fragment.length(); i++) {
            char c = fragment.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '_' && c != '[' && c != ']') {
                stop = i;
                break;
            }
        }
        return stop == 0 ? null : fragment.substring(0, stop);
    }
}
