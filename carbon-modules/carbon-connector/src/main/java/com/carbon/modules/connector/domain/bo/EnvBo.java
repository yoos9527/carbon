package com.carbon.modules.connector.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 环境新增/修改入参。
 *
 * <p><b>为什么环境值得一个正经 CRUD</b>：{@code {{env.xxx}}} 是模板里唯一的「部署期变量」出口。
 * 让它只能靠 SQL 改，等于把「换一套测试域名」变成一次 DBA 工单——而这恰恰是最常发生的事。
 *
 * <p>校验边界：编码格式、变量形状、前缀协议在这里拦；「变量有没有被某个模板用到」
 * 属于使用方的问题（删变量可能让某个接口渲染失败），不在保存时做全库扫描。
 *
 * @author carbon
 */
public class EnvBo {

    /** 环境编码；网关/日志里会按它定位环境，所以限定字符集 */
    @NotBlank(message = "环境编码不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9_-]{1,63}$",
            message = "环境编码只允许小写字母、数字、下划线、短横线，且以字母开头，长度 2-64")
    private String code;

    @NotBlank(message = "环境名称不能为空")
    @Size(max = 128, message = "环境名称不能超过 128 字")
    private String name;

    /** 可选：渲染时拼在连接器 base_url 前，如 {@code https://api-test.example.com} */
    @Size(max = 512, message = "base_url 前缀不能超过 512 字")
    private String baseUrlPrefix;

    /**
     * 环境变量，供 {@code {{env.xxx}}} 取值。
     *
     * <p>{@code null} 表示「本次不修改」；要清空请传空对象 {@code {}}。
     */
    private Map<String, Object> vars;

    /** 是否设为默认环境；只有 1 个环境时强制为默认 */
    private Boolean isDefault;

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

    public String getBaseUrlPrefix() {
        return baseUrlPrefix;
    }

    public void setBaseUrlPrefix(String baseUrlPrefix) {
        this.baseUrlPrefix = baseUrlPrefix;
    }

    public Map<String, Object> getVars() {
        return vars;
    }

    public void setVars(Map<String, Object> vars) {
        this.vars = vars;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
