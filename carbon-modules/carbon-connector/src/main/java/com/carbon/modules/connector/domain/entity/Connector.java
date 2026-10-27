package com.carbon.modules.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.carbon.common.mybatis.core.domain.BaseEntity;

/**
 * 连接器：一个三方系统 = 一条记录。
 *
 * <p>{@code base_url} 与接口 {@code path} 都支持 {@code {{env.xxx}}} 模板，
 * 环境差异不靠建多个连接器，而靠切换环境（{@code env} 表）。
 *
 * @author carbon
 */
@TableName("connector")
public class Connector extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 网关路径用，全局唯一，如 {@code wechat} */
    private String code;

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
