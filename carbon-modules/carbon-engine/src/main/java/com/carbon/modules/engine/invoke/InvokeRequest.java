package com.carbon.modules.engine.invoke;

import java.util.Map;

/**
 * 试调入参。
 *
 * @param connectorCode 连接器编码
 * @param operationCode 接口编码
 * @param input         业务入参，模板里以 {@code {{input.xxx}}} 引用
 * @param envCode       目标环境；{@code null} 表示默认环境
 * @param headerOverrides 临时覆盖的请求头（试调面板专用，不落库）
 * @param dryRun        {@code true} 时只渲染并回显将发出的请求，不真正调用三方
 * @param source        来源标记（{@code CONSOLE}/{@code GW}/{@code FLOW}），只用于写日志
 * @param appId         调用方应用 id（仅网关有），只用于写日志
 * @author carbon
 */
public record InvokeRequest(String connectorCode,
                            String operationCode,
                            Map<String, Object> input,
                            String envCode,
                            Map<String, String> headerOverrides,
                            boolean dryRun,
                            String source,
                            Long appId) {

    public InvokeRequest {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
