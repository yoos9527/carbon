package com.carbon.modules.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.carbon.common.mybatis.core.domain.BaseEntity;

/**
 * 环境：提供 {@code {{env.xxx}}} 变量；连接器 base_url 与接口 path 里的域名差异靠它切。
 *
 * @author carbon
 */
@TableName("env")
public class Env extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 唯一编码，如 dev/test/prod */
    private String code;

    private String name;

    /** 可选，渲染时拼在 base_url 前 */
    private String baseUrlPrefix;

    /** JSON：{@code {"appId":"..."}}，供模板取 {@code {{env.xxx}}} */
    private String vars;

    /** 1 表示默认环境 */
    private Integer isDefault;

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

    public String getBaseUrlPrefix() {
        return baseUrlPrefix;
    }

    public void setBaseUrlPrefix(String baseUrlPrefix) {
        this.baseUrlPrefix = baseUrlPrefix;
    }

    public String getVars() {
        return vars;
    }

    public void setVars(String vars) {
        this.vars = vars;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
