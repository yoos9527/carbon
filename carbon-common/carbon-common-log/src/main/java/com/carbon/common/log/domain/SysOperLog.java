package com.carbon.common.log.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 操作日志。
 *
 * <p><b>不继承 {@code BaseEntity}</b>：日志不需要软删除（没人会「恢复一条日志」），
 * 也不该被 {@code @TableLogic} 加上 {@code deleted = 0} 条件——
 * 表里根本没有那一列，加了就会报 {@code Unknown column}。
 *
 * @author carbon
 */
@TableName("sys_oper_log")
public class SysOperLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 模块名，如「连接器」 */
    private String title;

    /** INSERT / UPDATE / DELETE / EXECUTE / OTHER */
    private String businessType;

    /** {@code ConnectorController.create} 这类，便于定位代码 */
    private String method;

    /** HTTP 方法 */
    private String requestMethod;

    /** 请求路径（不含 query：query 里可能带密钥） */
    private String requestUri;

    /** 调用方 IP（无登录，这是唯一能区分「谁」的线索） */
    private String clientIp;

    /** User-Agent，截断 255 */
    private String userAgent;

    /** 参数快照：脱敏后的 JSON，截断 2000 字符 */
    private String params;

    /** 1 成功 / 0 失败 */
    private Integer status;

    /** 失败原因，截断 500 字符 */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long durationMs;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBusinessType() {
        return businessType;
    }

    public void setBusinessType(String businessType) {
        this.businessType = businessType;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }

    public String getRequestUri() {
        return requestUri;
    }

    public void setRequestUri(String requestUri) {
        this.requestUri = requestUri;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
