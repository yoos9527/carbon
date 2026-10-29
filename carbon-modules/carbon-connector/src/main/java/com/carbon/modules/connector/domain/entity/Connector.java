package com.carbon.modules.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.carbon.common.mybatis.core.domain.BaseEntity;

/**
 * 接入点：一个合作方下的一组接口的公共配置。
 *
 * <p>类名沿用历史的 {@code Connector}（编排 DSL、调用日志、网关路径里都在用 {@code connectorId}/
 * {@code connector} 这两个词，改名只会把风险扩散到 100+ 处），但表已经是 {@code endpoint}。
 * 语义上：合作方是「哪家公司」，接入点是「用哪个地址+哪套默认头去调它」。
 *
 * <p>这里<b>没有</b>鉴权字段：密钥属于合作方（见 {@code partner_credential}），
 * 接入点只能选一条凭证，选不到就用合作方的默认凭证。
 *
 * <p>{@code base_url} 与接口 {@code path} 都支持 {@code {{env.xxx}}} 模板，
 * 环境差异不靠建多个接入点，而靠切换环境（{@code env} 表）。
 *
 * @author carbon
 */
@TableName("endpoint")
public class Connector extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属合作方 */
    private Long partnerId;

    /** 默认凭证；空表示用合作方的默认凭证 */
    private Long credentialId;

    /** 网关路径用，全局唯一，如 {@code wechat} */
    private String code;

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

    private String name;

    /** 分组，如 支付/物流/CRM */
    private String category;

    /** 支持模板，如 {@code https://{{env.host}}/api} */
    private String baseUrl;

    /** JSON：{@code Map<String,String>}，所有接口共享 */
    private String defaultHeaders;

    private Integer timeoutMs;

    /** JSON：{@code {maxAttempts,backoffMs,backoffMultiplier,retryOn[]}}，null 表示用平台默认 */
    private String retry;

    /** 0 草稿 / 1 启用 */
    private Integer status;

    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getDefaultHeaders() {
        return defaultHeaders;
    }

    public void setDefaultHeaders(String defaultHeaders) {
        this.defaultHeaders = defaultHeaders;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getRetry() {
        return retry;
    }

    public void setRetry(String retry) {
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
}
