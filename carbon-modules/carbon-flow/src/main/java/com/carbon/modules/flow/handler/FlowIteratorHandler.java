package com.carbon.modules.flow.handler;

import com.carbon.framework.dag.cmp.IteratorNodeHandler;
import com.carbon.framework.dag.dsl.node.ForeachNode;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;
import com.carbon.framework.expression.ExpressionEvaluator;
import com.carbon.modules.flow.executor.FlowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * FOREACH 的集合解析：把 {@code items} 表达式求值成可迭代对象。
 *
 * <p>为什么容忍这么多种形态（字符串 JSON、Map、单值）：画布上 {@code items} 是手填的，
 * 用户会写 {@code input.orders}、也会直接粘一段 JSON。宁可在这里多判几次类型，
 * 也不要让用户对着「不是数组」这种报错猜他自己写了什么。
 *
 * @author carbon
 */
@Component
public class FlowIteratorHandler implements IteratorNodeHandler {

    private final ExpressionEvaluator evaluator;
    private final ObjectMapper objectMapper;

    public FlowIteratorHandler(ExpressionEvaluator evaluator, ObjectMapper objectMapper) {
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
    }

    @Override
    public Iterator<?> iterate(NodeDef node, DagExecutionContext ctx) {
        ForeachNode foreach = (ForeachNode) node;
        Object value = evaluator.eval(foreach.items(), vars(ctx));
        return toList(value, foreach).iterator();
    }

    private List<Object> toList(Object value, ForeachNode foreach) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value instanceof Map<?, ?> map) {
            // Map 迭代成 [{"key":..,"value":..}, ...]：直接迭代 Map 时 item 只能是 value，
            // 用户拿不到 key，FOREACH 里几乎必然要用 key
            List<Object> pairs = new ArrayList<>(map.size());
            map.forEach((k, v) -> pairs.add(Map.of("key", k, "value", v)));
            return pairs;
        }
        if (value instanceof Object[] array) {
            return List.of(array);
        }
        if (value instanceof String text) {
            return parseJsonArray(text, foreach);
        }
        // 单值也当单元素集合：比抛「不是数组」友好，且语义上无害
        return List.of(value);
    }

    private List<Object> parseJsonArray(String text, ForeachNode foreach) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("[")) {
            throw new IllegalArgumentException("FOREACH 节点 " + foreach.id()
                    + " 的 items 求值结果不是数组：" + abbreviate(trimmed));
        }
        try {
            return objectMapper.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("FOREACH 节点 " + foreach.id()
                    + " 的 items 不是合法 JSON 数组：" + e.getMessage());
        }
    }

    private Map<String, Object> vars(DagExecutionContext ctx) {
        return FlowState.evalVarsOf(ctx);
    }

    private String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }
}
