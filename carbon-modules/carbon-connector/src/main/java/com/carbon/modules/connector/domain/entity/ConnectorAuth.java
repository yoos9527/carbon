package com.carbon.modules.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 连接器鉴权：与连接器 1:1，独立成表是为了整列加密与按类型扩展。
 * {@code config} 里所有敏感值都是 {@code ENC:v1:} 密文，读写必须过 {@code CryptoService}。
 *
 * @author carbon
 */
@TableName("connector_auth")
public class ConnectorAuth {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属连接器 */
    private Long connectorId;

    /** none|api_key|basic|bearer|oauth2_cc|login_token */
    private String authType;

    /** JSON，结构随 authType 变化；敏感字段单独加密 */
    private String config;

    /** 0 停用 / 1 启用 */
    private Integer status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConnectorId() {
        return connectorId;
    }

    public void setConnectorId(Long connectorId) {
        this.connectorId = connectorId;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
