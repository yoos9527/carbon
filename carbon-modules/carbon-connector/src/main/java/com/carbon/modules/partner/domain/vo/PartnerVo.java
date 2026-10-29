package com.carbon.modules.partner.domain.vo;

import com.carbon.modules.partner.domain.entity.Partner;

import java.time.LocalDateTime;

/**
 * 合作方出参。
 *
 * <p>带三个计数是为了回答「停用它会影响什么」——列表页不显示影响面，
 * 用户就只会在停用之后才发现某个业务挂了。
 *
 * @author carbon
 */
public class PartnerVo {

    private Long id;
    private String code;
    private String name;
    private Integer status;
    private String contactName;
    private String contactPhone;
    private String contractNo;
    private Integer priority;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 旗下接入点数量 */
    private Integer endpointCount;
    /** 旗下接口总数（含所有接入点） */
    private Integer operationCount;
    /** 凭证数量 */
    private Integer credentialCount;
    /** 默认凭证名称，列表页直接显示「用哪套 key」 */
    private String defaultCredentialName;

    public static PartnerVo of(Partner entity) {
        PartnerVo vo = new PartnerVo();
        vo.id = entity.getId();
        vo.code = entity.getCode();
        vo.name = entity.getName();
        vo.status = entity.getStatus();
        vo.contactName = entity.getContactName();
        vo.contactPhone = entity.getContactPhone();
        vo.contractNo = entity.getContractNo();
        vo.priority = entity.getPriority();
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getContractNo() {
        return contractNo;
    }

    public void setContractNo(String contractNo) {
        this.contractNo = contractNo;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
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

    public Integer getEndpointCount() {
        return endpointCount;
    }

    public void setEndpointCount(Integer endpointCount) {
        this.endpointCount = endpointCount;
    }

    public Integer getOperationCount() {
        return operationCount;
    }

    public void setOperationCount(Integer operationCount) {
        this.operationCount = operationCount;
    }

    public Integer getCredentialCount() {
        return credentialCount;
    }

    public void setCredentialCount(Integer credentialCount) {
        this.credentialCount = credentialCount;
    }

    public String getDefaultCredentialName() {
        return defaultCredentialName;
    }

    public void setDefaultCredentialName(String defaultCredentialName) {
        this.defaultCredentialName = defaultCredentialName;
    }
}
