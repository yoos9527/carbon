package com.carbon.modules.connector.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 接口（Operation）新增/修改入参。
 *
 * <p>{@code responseExtract} 用 List 而不是 Map：抽取结果是有序的，
 * 前端要按用户拖动后的顺序展示，Map 会把这个顺序丢掉。
 *
 * @author carbon
 */
public class OperationBo {

    @NotBlank(message = "编码不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "编码只允许字母、数字、下划线、短横线，长度 1-64")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称不能超过 128 字")
    private String name;

    @NotBlank(message = "HTTP 方法不能为空")
    @Pattern(regexp = "^(GET|POST|PUT|DELETE|PATCH)$", message = "只支持 GET/POST/PUT/DELETE/PATCH")
    private String method;

    @NotBlank(message = "路径不能为空")
    @Size(max = 512, message = "路径不能超过 512 字")
    private String path;

    /** 查询参数，值支持模板 */
    private Map<String, String> queryParams;

    /** 接口级 Header，会覆盖连接器的同名默认 Header */
    private Map<String, String> headers;

    @Pattern(regexp = "^(none|json|form|urlencoded|raw)$", message = "只支持 none/json/form/urlencoded/raw")
    private String bodyType;

    /** JSON 模板；{@code bodyType=json} 时必须是合法 JSON（占位符替换后仍要能解析） */
    private String bodyTemplate;

    private List<ExtractBo> responseExtract;

    private String preScript;
    private String postScript;

    @Min(value = 100, message = "脚本超时不能小于 100ms")
    @Max(value = 60_000, message = "脚本超时不能超过 60000ms")
    private Integer scriptTimeoutMs;

    @Min(value = 100, message = "超时不能小于 100ms")
    @Max(value = 120_000, message = "超时不能超过 120000ms")
    private Integer timeoutMs;

    private ConnectorBo.RetryBo retry;

    @Min(0)
    @Max(1)
    private Integer status;

    /**
     * 单条响应抽取规则。
     *
     * <p>{@code default} 是「接口没返回这个字段」时的兜底值——没有它，
     * 一个可选字段缺失就会让整个调用失败，这在实际三方接口里非常常见。
     *
     * @author carbon
     */
    public static class ExtractBo {

        @NotBlank(message = "抽取字段名不能为空")
        private String name;

        @NotBlank(message = "JSONPath 不能为空")
        private String jsonpath;

        private Object defaultValue;

        /** true 表示没取到就报 EXTRACT_FAILED，false 表示取不到给 null */
        private Boolean required;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getJsonpath() {
            return jsonpath;
        }

        public void setJsonpath(String jsonpath) {
            this.jsonpath = jsonpath;
        }

        public Object getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
        }

        public Boolean getRequired() {
            return required;
        }

        public void setRequired(Boolean required) {
            this.required = required;
        }
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

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, String> queryParams) {
        this.queryParams = queryParams;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
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

    public List<ExtractBo> getResponseExtract() {
        return responseExtract;
    }

    public void setResponseExtract(List<ExtractBo> responseExtract) {
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

    public ConnectorBo.RetryBo getRetry() {
        return retry;
    }

    public void setRetry(ConnectorBo.RetryBo retry) {
        this.retry = retry;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
