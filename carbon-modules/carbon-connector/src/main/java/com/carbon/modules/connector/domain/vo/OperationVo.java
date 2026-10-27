package com.carbon.modules.connector.domain.vo;

import com.carbon.modules.connector.domain.entity.ConnectorOperation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 接口展示对象。
 *
 * <p>直接把 JSON 列表原样透给前端，不做二次结构转换：前端 Monaco / 表格要的就是用户当初填的那份结构，
 * 后端一旦「规整」成自己的形状，编辑回填时就会出现「保存前保存后长得不一样」。
 *
 * @author carbon
 */
public class OperationVo {

    private Long id;
    private Long connectorId;
    private String code;
    private String name;
    private String method;
    private String path;
    private Map<String, Object> queryParams;
    private Map<String, Object> headers;
    private String bodyType;
    private String bodyTemplate;
    private List<Object> responseExtract;
    private String preScript;
    private String postScript;
    private Integer scriptTimeoutMs;
    private Integer timeoutMs;
    private Map<String, Object> retry;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static OperationVo of(ConnectorOperation entity,
                                 Map<String, Object> queryParams,
                                 Map<String, Object> headers,
                                 List<Object> responseExtract,
                                 Map<String, Object> retry) {
        OperationVo vo = new OperationVo();
        vo.id = entity.getId();
        vo.connectorId = entity.getConnectorId();
        vo.code = entity.getCode();
        vo.name = entity.getName();
        vo.method = entity.getMethod();
        vo.path = entity.getPath();
        vo.bodyType = entity.getBodyType();
        vo.bodyTemplate = entity.getBodyTemplate();
        vo.preScript = entity.getPreScript();
        vo.postScript = entity.getPostScript();
        vo.scriptTimeoutMs = entity.getScriptTimeoutMs();
        vo.timeoutMs = entity.getTimeoutMs();
        vo.status = entity.getStatus();
        vo.createdAt = entity.getCreatedAt();
        vo.updatedAt = entity.getUpdatedAt();
        vo.queryParams = queryParams;
        vo.headers = headers;
        vo.responseExtract = responseExtract;
        vo.retry = retry;
        return vo;
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

    public Map<String, Object> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, Object> queryParams) {
        this.queryParams = queryParams;
    }

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, Object> headers) {
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

    public List<Object> getResponseExtract() {
        return responseExtract;
    }

    public void setResponseExtract(List<Object> responseExtract) {
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

    public Map<String, Object> getRetry() {
        return retry;
    }

    public void setRetry(Map<String, Object> retry) {
        this.retry = retry;
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
