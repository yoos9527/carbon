package com.carbon.modules.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.carbon.common.mybatis.core.domain.BaseEntity;

/**
 * 接口（Operation）：接入点下一个可调用的能力，也是网关对外暴露的最小单位。
 *
 * <p>类名与字段名里的 {@code connectorId} 沿用历史命名（真实的列名也是 {@code connector_id}，
 * 表名已经是 {@code endpoint_operation}）：调用日志、编排运行记录、DSL 全都在用这个词。
 *
 * @author carbon
 */
@TableName("endpoint_operation")
public class ConnectorOperation extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属接入点 */
    private Long connectorId;

    /** 连接器内唯一，如 {@code createOrder} */
    private String code;

    private String name;

    /** GET|POST|PUT|DELETE|PATCH */
    private String method;

    /** 支持模板，如 {@code /order/{{input.orderId}}} */
    private String path;

    /** JSON：{@code Map<String,String 模板>} */
    private String queryParams;

    /** JSON：接口级 Header，覆盖连接器默认 */
    private String headers;

    /** none|json|form|urlencoded|raw */
    private String bodyType;

    /** JSON 模板：先解析成 JSON 树再逐叶子替换，保证类型与转义正确 */
    private String bodyTemplate;

    /** JSON：[{name, jsonpath, default}]，声明式抽取 */
    private String responseExtract;

    /** Groovy，可改 ctx.request / ctx.vars */
    private String preScript;

    /** Groovy，可改 ctx.response / ctx.vars */
    private String postScript;

    /** 脚本执行时限，默认 2000 */
    private Integer scriptTimeoutMs;

    /** null 表示继承连接器 */
    private Integer timeoutMs;

    /** JSON，null 表示继承接入点 */
    private String retry;

    /** 凭证模式：INHERIT 继承 / OVERRIDE 用 credentialId / NONE 不鉴权 */
    private String credentialMode;

    /** 仅 OVERRIDE 时有值，且必须属于同一合作方；切回继承时要能清空，所以强制写 null */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long credentialId;

    /** 0 草稿 / 1 启用 */
    private Integer status;

    public String getCredentialMode() {
        return credentialMode;
    }

    public void setCredentialMode(String credentialMode) {
        this.credentialMode = credentialMode;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(Long credentialId) {
        this.credentialId = credentialId;
    }

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

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(String queryParams) {
        this.queryParams = queryParams;
    }

    public String getHeaders() {
        return headers;
    }

    public void setHeaders(String headers) {
        this.headers = headers;
    }

    public String getBodyType() {
        return bodyType;
    }

    public void setBodyType(String bodyType) {
        this.bodyType = bodyType;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
    }

    public String getResponseExtract() {
        return responseExtract;
    }

    public void setResponseExtract(String responseExtract) {
        this.responseExtract = responseExtract;
    }

    public String getPreScript() {
        return preScript;
    }

    public void setPreScript(String preScript) {
        this.preScript = preScript;
    }

    public String getPostScript() {
        return postScript;
    }

    public void setPostScript(String postScript) {
        this.postScript = postScript;
    }

    public Integer getScriptTimeoutMs() {
        return scriptTimeoutMs;
    }

    public void setScriptTimeoutMs(Integer scriptTimeoutMs) {
        this.scriptTimeoutMs = scriptTimeoutMs;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getRetry() {
        return retry;
    }

    public void setRetry(String retry) {
        this.retry = retry;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
