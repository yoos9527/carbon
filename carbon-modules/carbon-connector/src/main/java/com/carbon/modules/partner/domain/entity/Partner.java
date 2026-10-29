package com.carbon.modules.partner.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.carbon.common.mybatis.core.domain.BaseEntity;

/**
 * 合作方：一家三方数据供应商（或集团下的一家公司）。
 *
 * <p>这一层**只放商业主体信息**：名称、编码、合同、联系人、状态。
 * 不放 URL（那是接入点的事），也不放密钥（那是凭证的事）。
 * 把三者分开的原因很实际：停用一家合作方要连坐它下面所有接口，
 * 而换一套密钥不应该影响任何调用方看到的入口。
 *
 * @author carbon
 */
@TableName("partner")
public class Partner extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务引用键，全局唯一 */
    private String code;

    private String name;

    /** 0 草稿 / 1 正常 / 2 停用 / 3 禁用（停用=可恢复，禁用=风控拉黑） */
    private Integer status;

    private String contactName;

    private String contactPhone;

    private String contractNo;

    /** 数值越小越优先，异常切换时按它排序 */
    private Integer priority;

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
