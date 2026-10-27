package com.carbon.framework.expression;

import java.util.List;

/**
 * 模板片段：字面量或占位符。
 *
 * <p>扫描结果既用于渲染（规则 2/3），也用于生成 {@link VarRef} 清单（表单生成、必填校验、试调预填）。
 *
 * @author carbon
 */
public sealed interface TemplateSegment permits TemplateSegment.Literal, TemplateSegment.Placeholder {

    /**
     * 字面量片段。
     *
     * @param text 原文
     */
    record Literal(String text) implements TemplateSegment {
    }

    /**
     * 占位符片段。
     *
     * @param expr    花括号内的表达式原文（已 trim）
     * @param start   起始位置（指向第一个 {@code &#123;}）
     * @param end     结束位置（指向最后一个 {@code &#125;} 之后）
     * @param raw     完整原文，含花括号
     */
    record Placeholder(String expr, int start, int end, String raw) implements TemplateSegment {
    }

    /**
     * 模板扫描结果。
     *
     * @param segments 片段列表
     */
    record ScanResult(List<TemplateSegment> segments) {

        /**
         * 是否「整个字符串就是一个占位符」——这是类型透传（规则 1）的判定条件。
         *
         * @return 是否是纯占位符
         */
        public boolean isSinglePlaceholder() {
            return segments.size() == 1 && segments.get(0) instanceof Placeholder;
        }

        /**
         * 唯一占位符（调用前请先确认 {@link #isSinglePlaceholder()}）。
         *
         * @return 占位符
         */
        public Placeholder single() {
            return (Placeholder) segments.get(0);
        }

        /**
         * 是否不含任何占位符。
         *
         * @return 是否含占位符
         */
        public boolean hasPlaceholder() {
            return segments.stream().anyMatch(Placeholder.class::isInstance);
        }
    }
}
