package com.carbon.common.sensitive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏工具的行为约定。
 *
 * <p>这些用例是从一次真实事故长出来的：连接器的 `api_key` 密钥被原样写进了
 * `sys_oper_log.params`——因为「密钥」在 JSON 里叫 {@code config.value}，
 * 而键名黑名单只认 {@code secret}/{@code password} 这类名字。
 * 所以这里的第一条用例专门钉住「容器键整体打码」。
 *
 * @author carbon
 */
class MaskingUtilsTest {

    @Test
    @DisplayName("auth.config 整块打码，authType 仍可读（审计要的是哪种鉴权，不是密钥）")
    void credentialContainerIsMaskedAsWhole() {
        String json = """
                {"code":"demo","auth":{"authType":"api_key","config":{"in":"header","name":"X-Api-Key","value":"super-secret-value"}}}
                """;

        String masked = MaskingUtils.maskJson(json, MaskingUtils.CREDENTIAL_CONTAINER_KEYS);

        assertThat(masked).contains("\"authType\":\"api_key\"");
        assertThat(masked).contains("\"config\":\"******\"");
        assertThat(masked).doesNotContain("super-secret-value");
        assertThat(masked).doesNotContain("X-Api-Key");
    }

    @Test
    @DisplayName("不加容器规则时只按键名黑名单打码——所以调用方必须显式传规则")
    void blacklistOnlyLeaksContainerValue() {
        String json = """
                {"auth":{"authType":"api_key","config":{"value":"super-secret-value"}}}
                """;

        String masked = MaskingUtils.maskJson(json);

        // 这正是修复前的数据泄漏形态，留作回归：证明「只靠黑名单」是不够的
        assertThat(masked).contains("super-secret-value");
    }

    @Test
    @DisplayName("常见的敏感键名（含大小写、下划线变体）都会被替换")
    void sensitiveKeyNamesAreMasked() {
        String json = """
                {"Authorization":"Bearer abcdefgh","api_secret":"abcdefgh","passWord":"abcdefgh","nested":{"accessToken":"abcdefgh"}}
                """;

        String masked = MaskingUtils.maskJson(json);

        assertThat(masked).doesNotContain("abcdefgh");
        assertThat(masked).contains("******");
    }

    @Test
    @DisplayName("短值不替换：把 6 个字符打成星号会让日志失去可读性，也没保护住什么")
    void shortValuesStayReadable() {
        String masked = MaskingUtils.maskJson("{\"token\":\"abc\"}");

        assertThat(masked).contains("\"token\":\"abc\"");
    }

    @Test
    @DisplayName("URL 只打码 query 的值，path 与键名保留")
    void maskUrlMasksQueryValuesOnly() {
        String masked = MaskingUtils.maskUrl("https://api.example.com/v1/items?token=abcdefgh&page=2");

        assertThat(masked).isEqualTo("https://api.example.com/v1/items?token=******&page=2");
    }

    @Test
    @DisplayName("Header 脱敏：显式名单（鉴权解析器刚注入的那个头）优先于键名黑名单")
    void maskHeadersHonoursExplicitKeys() {
        Map<String, String> masked = MaskingUtils.maskHeaders(
                Map.of("X-Wx-Ticket", "abcdefgh", "Content-Type", "application/json"),
                java.util.Set.of("X-Wx-Ticket"));

        assertThat(masked.get("X-Wx-Ticket")).isEqualTo("******");
        assertThat(masked.get("Content-Type")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("非法 JSON 原样返回：脱敏失败不该让请求失败")
    void brokenJsonIsReturnedAsIs() {
        assertThat(MaskingUtils.maskJson("{not json", MaskingUtils.CREDENTIAL_CONTAINER_KEYS))
                .isEqualTo("{not json");
    }

    @Test
    @DisplayName("credentialMode/credentialId/credentialType 不打码——黑名单子串 credential 会误伤，审计需要它们")
    void credentialMetaKeysStayReadable() {
        String json = """
                {"credentialMode":"OVERRIDE","credentialId":"2115008092107268098","credentialType":"api_key","config":{"value":"super-secret-value"}}
                """;

        String masked = MaskingUtils.maskJson(json, MaskingUtils.CREDENTIAL_CONTAINER_KEYS);

        assertThat(masked).contains("\"credentialMode\":\"OVERRIDE\"");
        assertThat(masked).contains("\"credentialId\":\"2115008092107268098\"");
        assertThat(masked).contains("\"credentialType\":\"api_key\"");
        assertThat(masked).doesNotContain("super-secret-value");
    }
}
