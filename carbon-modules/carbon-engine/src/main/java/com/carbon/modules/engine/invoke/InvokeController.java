package com.carbon.modules.engine.invoke;

import com.carbon.modules.invokelog.api.InvokeLogEvent;

import com.carbon.common.core.domain.R;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 试调接口。
 *
 * <p>路径里带连接器与接口编码，而不是 id：用户在看画布/文档时脑子里记的是
 * {@code wx/createOrder} 这种名字，不是雪花 id。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/invoke")
public class InvokeController {

    private final InvokeService invokeService;

    public InvokeController(InvokeService invokeService) {
        this.invokeService = invokeService;
    }

    /**
     * 执行一次调用。
     *
     * @param connectorCode 连接器编码
     * @param operationCode 接口编码
     * @param envCode       环境编码，省略取默认
     * @param dryRun        {@code true} 只渲染不发送，用于「看看会发出什么」
     * @param body          请求体：{@code {"input":{...},"headerOverrides":{...}}}
     */
    @PostMapping("/{connectorCode}/{operationCode}")
    public R<InvokeResult> invoke(@PathVariable String connectorCode,
                                  @PathVariable String operationCode,
                                  @RequestParam(required = false) String envCode,
                                  @RequestParam(defaultValue = "false") boolean dryRun,
                                  @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        InvokeRequest request = new InvokeRequest(
                connectorCode,
                operationCode,
                asMap(payload.get("input")),
                envCode,
                asStringMap(payload.get("headerOverrides")),
                dryRun,
                InvokeLogEvent.SOURCE_CONSOLE,
                null);
        return R.ok(invokeService.invoke(request));
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    private Map<String, String> asStringMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, String> result = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (v != null) {
                    result.put(String.valueOf(k), String.valueOf(v));
                }
            });
            return result;
        }
        return Map.of();
    }
}
