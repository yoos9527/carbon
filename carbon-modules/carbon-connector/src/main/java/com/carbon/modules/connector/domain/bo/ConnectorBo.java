package com.carbon.modules.connector.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 接入点新增/修改入参。
 *
 * <p>这里的校验只做「明显不合法」的拦截（长度、格式、范围）；
 * 「base_url 能不能连通」「模板占位符有没有拼错」放到试调与保存前校验里——
 * 在表单校验阶段做网络请求会让用户点保存就卡三秒。
 *
 * <p>刻意**没有**鉴权字段：密钥属于合作方，接入点只能选一条凭证。
 *
 * @author carbon
 */
public class ConnectorBo {

    /** 网关路径里会用到，只允许小写字母、数字、下划线、短横线 */
    @NotBlank(message = "编码不能为空")
    @Pattern(regexp = "^[a-z0-9_-]{2,64}$", message = "编码只允许小写字母、数字、下划线、短横线，长度 2-64")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称不能超过 128 字")
    private String name;

    @Size(max = 64, message = "分组不能超过 64 字")
    private String category;

    @NotBlank(message = "base_url 不能为空")
    @Size(max = 512, message = "base_url 不能超过 512 字")
    private String baseUrl;

    /** 所有接口共享的 Header；值支持 {@code {{env.xxx}}} */
    private Map<String, String> defaultHeaders;

    @Min(value = 100, message = "超时不能小于 100ms")
    @Max(value = 120_000, message = "超时不能超过 120000ms")
    private Integer timeoutMs;

    @Valid
    private RetryBo retry;

    /** 所属合作方 */
    @NotNull(message = "所属合作方不能为空")
    private Long partnerId;

    /** 默认凭证；留空表示用合作方的默认凭证 */
    private Long credentialId;

    /** 0 草稿 / 1 启用 */
    @Min(0)
    @Max(1)
    private Integer status;

    @Size(max = 512, message = "备注不能超过 512 字")
    private String remark;

    /**
     * 重试策略。默认不重试——非幂等的 POST 被重试一次就可能重复下单。
     *
     * @author carbon
     */
    public static class RetryBo {

        @Min(value = 0, message = "重试次数不能为负")
        @Max(value = 5, message = "重试次数上限 5，再多就不是重试而是攻击了")
        private Integer maxAttempts = 0;

        @Min(value = 0, message = "退避不能为负")
        @Max(value = 60_000, message = "退避上限 60000ms")
        private Long backoffMs = 0L;

        @Min(value = 1, message = "退避倍数至少 1")
        @Max(value = 10, message = "退避倍数上限 10")
        private Double backoffMultiplier = 2.0D;

        /** 只对这些状态码重试 */
        private List<Integer> retryOn;

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Long getBackoffMs() {
            return backoffMs;
        }

        public void setBackoffMs(Long backoffMs) {
            this.backoffMs = backoffMs;
        }

        public Double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(Double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public List<Integer> getRetryOn() {
            return retryOn;
        }

        public void setRetryOn(List<Integer> retryOn) {
            this.retryOn = retryOn;
        }
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Map<String, String> getDefaultHeaders() {
        return defaultHeaders;
    }

    public void setDefaultHeaders(Map<String, String> defaultHeaders) {
        this.defaultHeaders = defaultHeaders;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public RetryBo getRetry() {
        return retry;
    }

    public void setRetry(RetryBo retry) {
        this.retry = retry;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(Long partnerId) {
        this.partnerId = partnerId;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(Long credentialId) {
        this.credentialId = credentialId;
    }
}
