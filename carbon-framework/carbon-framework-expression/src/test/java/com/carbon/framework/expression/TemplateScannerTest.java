package com.carbon.framework.expression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模板扫描器单测：重点覆盖规则 6（引号内的 {@code }} 不能截断）与未闭合报错。
 *
 * @author carbon
 */
class TemplateScannerTest {

    @Test
    @DisplayName("字面量与占位符交替切分")
    void shouldSplitLiteralsAndPlaceholders() {
        TemplateSegment.ScanResult result = TemplateScanner.scan("A-{{input.id}}-B");

        List<TemplateSegment> segments = result.segments();
        assertThat(segments).hasSize(3);
        assertThat(((TemplateSegment.Literal) segments.get(0)).text()).isEqualTo("A-");
        assertThat(((TemplateSegment.Placeholder) segments.get(1)).expr()).isEqualTo("input.id");
        assertThat(((TemplateSegment.Literal) segments.get(2)).text()).isEqualTo("-B");
        assertThat(result.isSinglePlaceholder()).isFalse();
    }

    @Test
    @DisplayName("纯占位符被识别为单占位符（规则 1 的判定条件）")
    void shouldDetectSinglePlaceholder() {
        TemplateSegment.ScanResult result = TemplateScanner.scan("{{input.page ?? 1}}");

        assertThat(result.isSinglePlaceholder()).isTrue();
        assertThat(result.single().expr()).isEqualTo("input.page ?? 1");
        assertThat(result.single().start()).isZero();
        assertThat(result.single().end()).isEqualTo(19);
    }

    @Test
    @DisplayName("规则 6：表达式里的 }} 被单引号包住时不截断")
    void shouldNotBreakOnClosingBracesInsideQuotes() {
        TemplateSegment.ScanResult result = TemplateScanner.scan("{{jsonpath(input.payload, '$.a}}b')}}");

        assertThat(result.isSinglePlaceholder()).isTrue();
        assertThat(result.single().expr()).isEqualTo("jsonpath(input.payload, '$.a}}b')");
    }

    @Test
    @DisplayName("规则 6：默认值里的 } 字面量不截断")
    void shouldNotBreakOnBraceInDefaultValue() {
        TemplateSegment.ScanResult result = TemplateScanner.scan("{{input.x ?? '}'}}");

        assertThat(result.isSinglePlaceholder()).isTrue();
        assertThat(result.single().expr()).isEqualTo("input.x ?? '}'");
    }

    @Test
    @DisplayName("转义引号内的 }} 也不截断")
    void shouldRespectEscapedQuotes() {
        TemplateSegment.ScanResult result = TemplateScanner.scan("{{jsonpath(input.p, '$.a\\'}}')}}");

        assertThat(result.isSinglePlaceholder()).isTrue();
    }

    @Test
    @DisplayName("无占位符时整段是字面量")
    void shouldReturnSingleLiteral() {
        TemplateSegment.ScanResult result = TemplateScanner.scan("plain text");

        assertThat(result.hasPlaceholder()).isFalse();
        assertThat(result.segments()).hasSize(1);
    }

    @Test
    @DisplayName("占位符未闭合 → PARAM_INVALID，并带位置与变量路径")
    void shouldFailOnUnclosedPlaceholder() {
        assertThatThrownBy(() -> TemplateScanner.scan("A-{{input.id"))
                .isInstanceOf(RenderException.class)
                .satisfies(e -> {
                    RenderException ex = (RenderException) e;
                    assertThat(ex.getCode()).isEqualTo(RenderException.PARAM_INVALID);
                    assertThat(ex.getMessage()).contains("未闭合");
                    assertThat(ex.getPosition()).isEqualTo(2);
                    assertThat(ex.getVarPath()).isEqualTo("input.id");
                });
    }

    @Test
    @DisplayName("空占位符 {{}} → PARAM_INVALID")
    void shouldFailOnEmptyPlaceholder() {
        assertThatThrownBy(() -> TemplateScanner.scan("{{  }}"))
                .isInstanceOf(RenderException.class)
                .hasMessageContaining("空占位符");
    }

    @Test
    @DisplayName("null / 空串 → 空结果")
    void shouldHandleNullAndEmpty() {
        assertThat(TemplateScanner.scan(null).segments()).isEmpty();
        assertThat(TemplateScanner.scan("").segments()).isEmpty();
    }
}
