package com.carbon.modules.partner.domain.vo;

import com.carbon.modules.partner.domain.entity.PartnerCredential;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 凭证出参。
 *
 * @author carbon
 */
public class CredentialVo {

    private Long id;
    private Long partnerId;
    private String code;
    private String name;
    private String type;
    /** 敏感字段已换成 ****** 的配置 */
    private Map<String, Object> configMasked;
    private Integer isDefault;
    private Integer status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CredentialVo of(PartnerCredential entity) {
        CredentialVo vo = new CredentialVo();
        vo.id = entity.getId();
        vo.partnerId = entity.getPartnerId();
        vo.code = entity.getCode();
        vo.name = entity.getName();
        vo.type = entity.getType();
        vo.isDefault = entity.getIsDefault();
        vo.status = entity.getStatus();
        vo.effectiveFrom = entity.getEffectiveFrom();
        vo.effectiveTo = entity.getEffectiveTo();
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

    public Long getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(Long partnerId) {
        this.partnerId = partnerId;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getConfigMasked() {
        return configMasked;
    }

    public void setConfigMasked(Map<String, Object> configMasked) {
        this.configMasked = configMasked;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDateTime effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDateTime getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDateTime effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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
}
