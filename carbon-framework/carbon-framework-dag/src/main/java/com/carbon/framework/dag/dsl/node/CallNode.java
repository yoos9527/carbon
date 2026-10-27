package com.carbon.framework.dag.dsl.node;

import com.carbon.framework.dag.dsl.OnError;
import com.carbon.framework.dag.dsl.Retry;

import java.util.Map;

/**
 * 连接器调用节点。
 *
 * <p>{@code input} 的每个值是模板串（{@code {{vars.x}}} 形式），运行时渲染成该接口的请求参数；
 * {@code output} 是「变量名 → JSONPath」，把响应抽进 {@code vars}。
 *
 * <p>三方 4xx/5xx 属于**业务结果**：默认不抛异常，状态码与响应体写进 {@code vars.<nodeId>}；
 * 只有连接失败、读超时这类技术故障才抛 {@code DagNodeException}。
 *
 * @param id         节点 id
 * @param label      画布展示名
 * @param connector  连接器 key
 * @param operation  接口 key
 * @param input      入参模板
 * @param output     出参抽取：变量名 → JSONPath
 * @param onError    失败处置，null 视为 STOP
 * @param retry      重试策略，null 视为不重试
 * @author carbon
 */
public record CallNode(String id, String label, String connector, String operation,
                       Map<String, Object> input, Map<String, String> output,
                       OnError onError, Retry retry) implements NodeDef {

    public CallNode {
        Nodes.requireId(id, "CONNECTOR_CALL");
        Nodes.requireText(connector, "CONNECTOR_CALL.connector");
        Nodes.requireText(operation, "CONNECTOR_CALL.operation");
        input = input == null ? Map.of() : Map.copyOf(input);
        output = output == null ? Map.of() : Map.copyOf(output);
        onError = onError == null ? OnError.STOP : onError;
        retry = Retry.orDefault(retry);
    }

    /** @return 展示名，缺省回落到 id */
    public String displayName() {
        return label == null || label.isBlank() ? id : label;
    }
}
