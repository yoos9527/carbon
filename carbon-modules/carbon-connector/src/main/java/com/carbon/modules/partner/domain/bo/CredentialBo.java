package com.carbon.modules.partner.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 凭证新增/修改入参。
 *
 * <p>{@code config} 里放**明文**密钥：加密是服务端这一层的事，前端不该看到 {@code ENC:v1:} 密文，
 * 更不该自己加密（那样主密钥就跑到浏览器里了）。
 * 更新时某个敏感字段留空或等于 {@code ******} → 视为「不修改」，避免编辑凭证时把密钥清空。
 *
 * <p>两种类型的 config 形状：
 * <ul>
 *   <li>{@code header}：{@code {"headers":[{"name":"X-Api-Key","value":"xxx","in":"header"}]}}（in 可省，默认 header，也可为 query）；</li>
 *   <li>{@code script}：{@code {"script":"ctx.headers['Authorization']='Bearer '+ctx.secrets.token",
 *       "secrets":{"token":"xxx"},"ttlSeconds":3600,"sensitiveKeys":["Authorization"]}}。</li>
 * </ul>
 *
 * @author carbon
 */
public class CredentialBo {

    @NotBlank(message = "编码不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_-]{2,64}$", message = "编码只允许字母、数字、下划线、短横线，长度 2-64")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称不能超过 128 字")
    private String name;

    @NotBlank(message = "类型不能为空")
    @Pattern(regexp = "^(header|script)$", message = "类型只支持 header（静态请求头）/ script（Groovy 脚本）")
    private String type;

    private Map<String, Object> config;

    /** 是否设为该合作方的默认凭证 */
    private Boolean isDefault;

    /** 0 停用 / 1 启用 */
    @Min(0)
    @Max(1)
    private Integer status;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
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
}
