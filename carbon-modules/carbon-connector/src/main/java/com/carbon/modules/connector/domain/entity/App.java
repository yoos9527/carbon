package com.carbon.modules.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.LocalDateTime;

/**
 * 调用方应用：业务系统接入网关的凭证。
 * 本表没有 {@code deleted} 列——不用逻辑删除：应用下线就是 {@code status=0}，
 * 要彻底失效就走真实 DELETE（网关拿不到 appKey 自然拒绝），所以不继承 BaseEntity。
 *
 * @author carbon
 */
@TableName("app")
public class App {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 应用名 */
    private String name;

    /** 暴露给业务方，生成时带 {@code ck_} 前缀 */
    private String appKey;

    /** AES-GCM 密文，永不出现在任何响应体里 */
    private String appSecretEnc;

    /** JSON 数组：允许的连接器 id，null 表示不限制 */
    private String allowedConnectors;

    /** 应用级 QPS 上限，默认 50 */
    private Integer rateLimitQps;

    /** JSON 数组：CIDR 列表，null 表示不限制 */
    private String ipWhitelist;

    /** 0 停用 / 1 启用 */
    private Integer status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecretEnc() {
        return appSecretEnc;
    }

    public void setAppSecretEnc(String appSecretEnc) {
        this.appSecretEnc = appSecretEnc;
    }

    public String getAllowedConnectors() {
        return allowedConnectors;
    }

    public void setAllowedConnectors(String allowedConnectors) {
        this.allowedConnectors = allowedConnectors;
    }

    public Integer getRateLimitQps() {
        return rateLimitQps;
    }

    public void setRateLimitQps(Integer rateLimitQps) {
        this.rateLimitQps = rateLimitQps;
    }

    public String getIpWhitelist() {
        return ipWhitelist;
    }

    public void setIpWhitelist(String ipWhitelist) {
        this.ipWhitelist = ipWhitelist;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
