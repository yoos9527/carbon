package com.carbon.modules.partner.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 合作方新增/修改入参。
 *
 * <p>刻意**没有** baseUrl 与密钥字段：这一层是商业主体，不是技术配置。
 * 如果这里放开一个 URL，很快就会出现「合作方上的 URL」和「接入点上的 URL」两个真相。
 *
 * @author carbon
 */
public class PartnerBo {

    @NotBlank(message = "编码不能为空")
    @Pattern(regexp = "^[a-z0-9_-]{2,64}$", message = "编码只允许小写字母、数字、下划线、短横线，长度 2-64")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称不能超过 128 字")
    private String name;

    /** 0 草稿 / 1 正常 / 2 停用 / 3 禁用 */
    @Min(0)
    @Max(3)
    private Integer status;

    @Size(max = 64, message = "联系人不能超过 64 字")
    private String contactName;

    @Size(max = 32, message = "联系电话不能超过 32 字")
    private String contactPhone;

    @Size(max = 128, message = "合同编号不能超过 128 字")
    private String contractNo;

    @Min(0)
    @Max(9999)
    private Integer priority;

    @Size(max = 512, message = "备注不能超过 512 字")
    private String remark;

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
}
