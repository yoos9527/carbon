package com.carbon.modules.connector.domain.vo;

import com.carbon.modules.connector.domain.entity.Connector;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 连接器展示对象（列表页与详情页共用）。
 *
 * <p>和实体分开的原因只有两个：
 * <ol>
 *   <li>鉴权配置必须脱敏后才能出现在这里，实体上是密文；</li>
 *   <li>列表页要带「接口数量」这类聚合值，不属于实体字段。</li>
 * </ol>
 *
 * @author carbon
 */
public class ConnectorVo {

    private Long id;
    private String code;
    private String name;
    private String category;
    private String baseUrl;
    private Map<String, String> defaultHeaders;
    private Integer timeoutMs;
    private Map<String, Object> retry;
    private Integer status;
    private String remark;

    /** 所属合作方 */
    private Long partnerId;
    private String partnerName;
    /** 默认凭证；{@code null} 表示用合作方的默认凭证 */
    private Long credentialId;
    private String credentialName;

    private Integer operationCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ConnectorVo of(Connector entity) {
        ConnectorVo vo = new ConnectorVo();
        vo.id = entity.getId();
        vo.code = entity.getCode();
        vo.name = entity.getName();
        vo.category = entity.getCategory();
        vo.baseUrl = entity.getBaseUrl();
        vo.partnerId = entity.getPartnerId();
        vo.credentialId = entity.getCredentialId();
        vo.timeoutMs = entity.getTimeoutMs();
        vo.status = entity.getStatus();
        vo.remark = entity.getRemark();
        vo.createdAt = entity.getCreatedAt();
        vo.updatedAt = entity.getUpdatedAt();
        return vo;
    }

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

    public Map<String, Object> getRetry() {
        return retry;
    }

    public void setRetry(Map<String, Object> retry) {
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

    public String getPartnerName() {
        return partnerName;
    }

    public void setPartnerName(String partnerName) {
        this.partnerName = partnerName;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(Long credentialId) {
        this.credentialId = credentialId;
    }

    public String getCredentialName() {
        return credentialName;
    }

    public void setCredentialName(String credentialName) {
        this.credentialName = credentialName;
    }

    public Integer getOperationCount() {
        return operationCount;
    }

    public void setOperationCount(Integer operationCount) {
        this.operationCount = operationCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** {@code List} 只用于让 {@code operationCount} 的语义在 IDE 里可见 */
    @SuppressWarnings("unused")
    private static final List<String> UNUSED = List.of();
}
