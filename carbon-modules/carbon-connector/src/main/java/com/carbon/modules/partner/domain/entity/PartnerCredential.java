package com.carbon.modules.partner.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.carbon.common.mybatis.core.domain.BaseEntity;

/**
 * 合作方凭证：一套密钥。
 *
 * <p>单独成表而不是挂在接入点上，是因为「一套密钥」和「一个入口」不是一对一关系：
 * 同一家公司的查询接口与回传接口经常是两套 key；换 key 时也不该动 URL。
 *
 * <p>{@code config} 里敏感字段是 {@code ENC:v1:} 密文（见 {@code CredentialConfigCodec}），
 * 非敏感字段（tokenUrl、clientId、scope）明文保留，方便排障时一眼看出配的是哪个地址。
 *
 * @author carbon
 */
@TableName("partner_credential")
public class PartnerCredential extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long partnerId;

    /** 合作方内唯一，如 {@code query} / {@code callback} */
    private String code;

    private String name;

    /** header（静态请求头）/ script（Groovy 脚本）；V9 之前的旧六型已迁移或停用 */
    private String type;

    /** JSON，敏感字段为密文 */
    private String config;

    /** 1 = 该合作方的默认凭证（接口与接入点都没指定时用它） */
    private Integer isDefault;

    /** 0 停用 / 1 启用 */
    private Integer status;

    private java.time.LocalDateTime effectiveFrom;

    private java.time.LocalDateTime effectiveTo;

    private String remark;

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

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
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

    public java.time.LocalDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(java.time.LocalDateTime effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public java.time.LocalDateTime getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(java.time.LocalDateTime effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
