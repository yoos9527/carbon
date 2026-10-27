package com.carbon.modules.gateway.gw;

import com.carbon.common.json.JsonUtils;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.connector.service.IConnectorService;
import com.carbon.modules.connector.service.IOperationService;
import com.carbon.modules.flow.domain.entity.Flow;
import com.carbon.modules.flow.service.IFlowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关自有 OpenAPI 文档：{@code GET /v3/api-docs/gateway}。
 *
 * <p>为什么不交给 springdoc 自动扫描：springdoc 只能根据 Controller 的
 * {@code @PathVariable} 生成「{@code /gw/{connectorCode}/{operationCode}}」这一条泛化路径，
 * 而接入方真正需要的是「我这个应用能调哪些接口、每个接口要传什么」——
 * 那是**数据库里**的连接器与接口，不是 Java 方法的签名。
 *
 * <p>只列**启用**的连接器与接口：文档本身就是一份对外能力清单，
 * 把草稿接口写进去等于告诉对方「这里有东西，只是还没开门」。
 *
 * <p>不需要签名——它和 {@code /gw/health} 一样是公开信息，
 * 只是把公开信息按 OpenAPI 的格式给出来，省掉接入方手抄的一步。
 *
 * @author carbon
 */
@RestController
public class GatewayOpenApiController {

    private final IConnectorService connectorService;
    private final IOperationService operationService;
    private final IFlowService flowService;

    public GatewayOpenApiController(IConnectorService connectorService,
                                    IOperationService operationService,
                                    IFlowService flowService) {
        this.connectorService = connectorService;
        this.operationService = operationService;
        this.flowService = flowService;
    }

    @GetMapping("/v3/api-docs/gateway")
    public Map<String, Object> document() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");
        doc.put("info", info());
        doc.put("servers", List.of(Map.of("url", "/", "description", "同源部署；网关路径以 /gw 开头")));
        doc.put("security", List.of(securityRequirement()));
        doc.put("components", components());
        doc.put("x-carbon-signature", signatureContract());

        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("/gw/health", healthPath());

        List<String> tags = new ArrayList<>();
        for (Connector connector : connectorService.listEnabled()) {
            tags.add(connector.getCode());
            for (ConnectorOperation operation : operationService.listEnabled(connector.getId())) {
                paths.put("/gw/" + connector.getCode() + "/" + operation.getCode(),
                        Map.of("post", operationPathItem(connector, operation)));
            }
        }
        List<Flow> flows = flowService.listPublished();
        if (!flows.isEmpty()) {
            paths.put("/gw/flows/{flowCode}", Map.of("post", flowPathItem(flows)));
        }
        doc.put("paths", paths);
        doc.put("tags", tags.stream().map(code -> Map.of("name", code)).toList());
        return doc;
    }

    // ------------------------------------------------------------------ 片段

    private Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", "Carbon 连接器网关");
        info.put("version", "1.0.0");
        info.put("description", """
                平台把「连接器 + 接口」与「编排流程」直接暴露为 HTTP 入口。本文件由平台按当前**启用**的
                连接器/接口/流程实时生成，新增一个接口不需要改这里。

                - 连接器入口 `/gw/{connectorCode}/{operationCode}`：一次三方调用，返回抽取后的字段；
                - 编排入口 `/gw/flows/{flowCode}`：一段流程（多次调用/判断/循环），同步返回执行摘要；
                - 健康检查 `/gw/health`：不需要签名。

                所有请求都必须带 X-App-Key / X-Timestamp / X-Nonce / X-Sign 四个头，
                签名规则见 `x-carbon-signature`。""");
        return info;
    }

    private Map<String, Object> components() {
        Map<String, Object> schemes = new LinkedHashMap<>();
        schemes.put("AppKey", apiKeyHeader("X-App-Key", "应用标识"));
        schemes.put("Timestamp", apiKeyHeader("X-Timestamp", "毫秒时间戳，允许 ±5 分钟偏差"));
        schemes.put("Nonce", apiKeyHeader("X-Nonce", "一次性随机串，≤64 字符，10 分钟内不可重复"));
        schemes.put("Sign", apiKeyHeader("X-Sign", "HMAC-SHA256 签名，Base64"));

        Map<String, Object> gwResult = new LinkedHashMap<>();
        gwResult.put("type", "object");
        gwResult.put("description", "网关统一响应。注意 code 是**语义码**（成功为字符串 \"0\"），与 HTTP 状态码是两套东西。");
        gwResult.put("properties", new LinkedHashMap<>(Map.of(
                "code", Map.of("type", "string", "description", "语义码，成功为 \"0\""),
                "message", Map.of("type", "string"),
                "data", Map.of("nullable", true, "description", "业务数据：连接器入口是抽取结果，编排入口是执行摘要"),
                "traceId", Map.of("type", "string", "description", "链路 id，排查时把它给对方即可"),
                "providerStatus", Map.of("type", "integer", "nullable", true, "description", "三方 HTTP 状态码"),
                "costMs", Map.of("type", "integer", "nullable", true, "description", "平台侧耗时（含鉴权/渲染/重试）"))));

        Map<String, Object> schemas = new LinkedHashMap<>();
        schemas.put("GwResult", gwResult);

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("securitySchemes", schemes);
        components.put("schemas", schemas);
        return components;
    }

    private Map<String, Object> apiKeyHeader(String name, String description) {
        Map<String, Object> scheme = new LinkedHashMap<>();
        scheme.put("type", "apiKey");
        scheme.put("in", "header");
        scheme.put("name", name);
        scheme.put("description", description);
        return scheme;
    }

    private Map<String, Object> securityRequirement() {
        Map<String, Object> requirement = new LinkedHashMap<>();
        requirement.put("AppKey", List.of());
        requirement.put("Timestamp", List.of());
        requirement.put("Nonce", List.of());
        requirement.put("Sign", List.of());
        return requirement;
    }

    private Map<String, Object> signatureContract() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("algorithm", "HMAC-SHA256");
        contract.put("encoding", "Base64");
        contract.put("stringToSign", "METHOD\\npath?query\\ntimestamp\\nnonce\\nsha256Hex(body)");
        contract.put("queryCanonicalization", "key 按字典序，同 key 多值保持原序，逐项 RFC3986 编码（空格是 %20）");
        contract.put("pathIncluded", "只含 path 与规范化 query，不含 scheme/host");
        contract.put("clockSkew", "±5 分钟");
        contract.put("example", """
                # 以 POST /gw/httpbin/get?x=1 空 body、timestamp=1730000000000、nonce=abc 为例
                stringToSign = "POST\\n/gw/httpbin/get?x=1\\n1730000000000\\nabc\\n"
                             + sha256Hex("")
                X-Sign = base64(hmacSha256(appSecret, stringToSign))""");
        return contract;
    }

    private Map<String, Object> healthPath() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tags", List.of("platform"));
        item.put("summary", "健康检查（免签名）");
        item.put("description", "只回答「进程还在」，不查库、不报版本号——这条路径是匿名开放的，不能泄露有信息量的内容。");
        item.put("operationId", "gatewayHealth");
        item.put("security", List.of());
        item.put("responses", Map.of("200", jsonResponse("UP")));
        return Map.of("get", item);
    }

    private Map<String, Object> operationPathItem(Connector connector, ConnectorOperation operation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tags", List.of(connector.getCode()));
        item.put("summary", connector.getName() + " - " + operation.getName());
        item.put("operationId", "call_" + connector.getCode() + "_" + operation.getCode());
        item.put("description", """
                平台按接口配置渲染 URL/报文，并注入鉴权后调用三方。
                body 顶层字段与 query 参数共同组成模板 input，同名时 **body 优先**（只有 body 能表达类型）。
                GET 与 POST 等价：签名里 body 参与摘要，两者没有本质区别。""");
        item.put("parameters", operationParameters(operation));
        item.put("requestBody", requestBody("模板 input"));
        item.put("responses", operationResponses());
        item.put("x-carbon-connector", connector.getCode());
        item.put("x-carbon-operation", operation.getCode());
        item.put("x-carbon-provider-method", operation.getMethod());
        item.put("x-carbon-provider-path", operation.getPath());
        return item;
    }

    private List<Object> operationParameters(ConnectorOperation operation) {
        List<Object> params = new ArrayList<>();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("name", GwGuard.HEADER_ENV);
        env.put("in", "header");
        env.put("required", false);
        env.put("description", "环境编码；不传则用平台默认环境");
        env.put("schema", Map.of("type", "string"));
        params.add(env);

        queryParamNames(operation).forEach(name -> {
            Map<String, Object> param = new LinkedHashMap<>();
            param.put("name", name);
            param.put("in", "query");
            param.put("required", false);
            param.put("description", "接口配置的 query 模板变量，可直接传字面值");
            param.put("schema", Map.of("type", "string"));
            params.add(param);
        });
        return params;
    }

    /** 接口配置里声明过的 query key——那是对方唯一有依据能猜到的参数名 */
    private List<String> queryParamNames(ConnectorOperation operation) {
        String json = operation.getQueryParams();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseMap(json);
            return parsed == null ? List.of() : new ArrayList<>(parsed.keySet());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private Map<String, Object> requestBody(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        schema.put("description", description);

        Map<String, Object> media = new LinkedHashMap<>();
        media.put("schema", schema);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("required", false);
        body.put("content", Map.of("application/json", media));
        return body;
    }

    private Map<String, Object> operationResponses() {
        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("200", jsonResponse("调用成功；data 为接口配置的抽取结果"));
        responses.put("400", jsonResponse("参数不合法（PARAM_INVALID）"));
        responses.put("401", jsonResponse("签名校验失败（SIGN_INVALID）：不区分 appKey 错误/签名错/时间窗/nonce 重放"));
        responses.put("403", jsonResponse("应用无权调用（APP_FORBIDDEN）：IP 白名单或连接器白名单"));
        responses.put("404", jsonResponse("连接器或接口不存在 / 已停用"));
        responses.put("429", jsonResponse("调用频率超限（RATE_LIMITED）"));
        responses.put("502", jsonResponse("三方 4xx/5xx、鉴权失败或响应抽取失败"));
        responses.put("504", jsonResponse("三方超时（PROVIDER_TIMEOUT）"));
        return responses;
    }

    private Map<String, Object> flowPathItem(List<Flow> flows) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tags", List.of("flows"));
        item.put("summary", "执行一个已发布的编排流程（同步）");
        item.put("operationId", "call_flow");
        item.put("description", """
                按 code 执行当前**已发布**的流程版本，同步返回执行摘要（runId/status/output/stepCount）。
                body 顶层字段与 query 组成流程 input。

                应用若配置了连接器白名单，平台会检查该流程实际引用到的每个连接器是否都在白名单内——
                否则「绕道流程」就能拿到白名单外的数据。""");

        Map<String, Object> pathParam = new LinkedHashMap<>();
        pathParam.put("name", "flowCode");
        pathParam.put("in", "path");
        pathParam.put("required", true);
        pathParam.put("schema", Map.of("type", "string"));
        pathParam.put("description", "流程编码，见 x-carbon-flows");
        item.put("parameters", List.of(pathParam));
        item.put("requestBody", requestBody("流程 input"));

        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("200", jsonResponse("执行成功（status=SUCCESS）；PARTIAL/FAILED 会以 500 返回，data 里仍有 runId 可查明细"));
        responses.put("401", jsonResponse("签名校验失败（SIGN_INVALID）"));
        responses.put("403", jsonResponse("应用无权调用该流程引用的连接器（APP_FORBIDDEN）"));
        responses.put("404", jsonResponse("流程不存在/已停用/尚未发布（FLOW_NOT_FOUND）"));
        responses.put("429", jsonResponse("调用频率超限（RATE_LIMITED）"));
        responses.put("500", jsonResponse("流程执行失败或被队列拒绝"));
        item.put("responses", responses);
        item.put("x-carbon-flows", flows.stream().map(f -> Map.of(
                "code", f.getCode(),
                "name", f.getName(),
                "version", f.getPublishedVersion())).toList());
        return item;
    }

    private Map<String, Object> jsonResponse(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$ref", "#/components/schemas/GwResult");
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("schema", schema);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("description", description);
        response.put("content", Map.of("application/json", media));
        return response;
    }
}
