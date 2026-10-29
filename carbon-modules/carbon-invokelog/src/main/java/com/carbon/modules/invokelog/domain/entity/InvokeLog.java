package com.carbon.modules.invokelog.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 调用日志。
 *
 * <p>这张表是**只写**的：没有任何 update 语句，也没有逻辑删除列。审计数据被改写就失去意义，
 * 清理只走「按月分区 DROP PARTITION」。
 *
 * <p>主键是 {@code (id, created_at)} 复合键（分区表要求分区键进主键），这里只标注 {@code id}，
 * 因为我们从不按主键更新——只 insert。
 *
 * @author carbon
 */
@TableName("invoke_log")
public class InvokeLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;

    /** {@code CONSOLE} / {@code GW} / {@code FLOW} */
    private String source;

    private Long appId;

    private Long flowRunId;

    private Long connectorId;

    private Long operationId;

    /** 合作方（调用发生时已解析到的那一层） */
    private Long partnerId;

    /** 实际使用的凭证；未配置鉴权时为 null */
    private Long partnerCredentialId;

    /** 凭证类型，便于按鉴权方式排查 401 */
    private String credentialType;

    private String method;

    /** 脱敏后的完整 URL */
    private String realUrl;

    /** JSON 字符串，键名敏感的值已替换为掩码 */
    private String reqHeadersMasked;

    private String reqBody;

    private Integer respStatus;

    private String respBody;

    private Integer durationMs;

    private String errCode;

    private String errMsg;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    public Long getFlowRunId() {
        return flowRunId;
    }

    public void setFlowRunId(Long flowRunId) {
        this.flowRunId = flowRunId;
    }

    public Long getConnectorId() {
        return connectorId;
    }

    public void setConnectorId(Long connectorId) {
        this.connectorId = connectorId;
    }

    public Long getOperationId() {
        return operationId;
    }

    public void setOperationId(Long operationId) {
        this.operationId = operationId;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(Long partnerId) {
        this.partnerId = partnerId;
    }

    public Long getPartnerCredentialId() {
        return partnerCredentialId;
    }

    public void setPartnerCredentialId(Long partnerCredentialId) {
        this.partnerCredentialId = partnerCredentialId;
    }

    public String getCredentialType() {
        return credentialType;
    }

    public void setCredentialType(String credentialType) {
        this.credentialType = credentialType;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getRealUrl() {
        return realUrl;
    }

    public void setRealUrl(String realUrl) {
        this.realUrl = realUrl;
    }

    public String getReqHeadersMasked() {
        return reqHeadersMasked;
    }

    public void setReqHeadersMasked(String reqHeadersMasked) {
        this.reqHeadersMasked = reqHeadersMasked;
    }

    public String getReqBody() {
        return reqBody;
    }

    public void setReqBody(String reqBody) {
        this.reqBody = reqBody;
    }

    public Integer getRespStatus() {
        return respStatus;
    }

    public void setRespStatus(Integer respStatus) {
        this.respStatus = respStatus;
    }

    public String getRespBody() {
        return respBody;
    }

    public void setRespBody(String respBody) {
        this.respBody = respBody;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrCode() {
        return errCode;
    }

    public void setErrCode(String errCode) {
        this.errCode = errCode;
    }

    public String getErrMsg() {
        return errMsg;
    }

    public void setErrMsg(String errMsg) {
        this.errMsg = errMsg;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
