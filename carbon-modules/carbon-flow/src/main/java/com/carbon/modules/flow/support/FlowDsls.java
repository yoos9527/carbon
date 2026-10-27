package com.carbon.modules.flow.support;

import com.carbon.framework.dag.dsl.DagDsl;
import com.carbon.framework.dag.graph.ScopeSet;
import com.carbon.framework.dag.graph.Scopes;
import com.carbon.framework.dag.validator.GraphValidator;
import com.carbon.framework.dag.validator.ValidationError;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 画布 JSON ↔ {@link DagDsl} 的边界。
 *
 * <p>为什么单独一层而不是各处直接 {@code objectMapper.readValue}：
 * <ul>
 *   <li>反序列化配置必须一致（{@code FAIL_ON_UNKNOWN_PROPERTIES=false} 是**故意**的：
 *       前端多传一个 {@code position} 不该让整个流程保存失败）；</li>
 *   <li>解析失败要转成「用户能看懂的一句话」，而不是 Jackson 的
 *       {@code Unexpected character} 堆栈——那是给开发者看的；</li>
 *   <li>校验与编译共用同一个 {@link ScopeSet}，避免两边对「哪个节点属于哪个子图」理解不一致。</li>
 * </ul>
 *
 * @author carbon
 */
public final class FlowDsls {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FlowDsls() {
    }

    /**
     * 解析 DSL。
     *
     * @throws IllegalArgumentException 结构不合法（消息可直接回给前端）
     */
    public static DagDsl parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("流程 DSL 不能为空");
        }
        try {
            DagDsl dsl = MAPPER.readValue(json, DagDsl.class);
            if (dsl.nodes() == null || dsl.nodes().isEmpty()) {
                throw new IllegalArgumentException("流程至少要有 START 与 END 两个节点");
            }
            return dsl;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("流程 DSL 不是合法 JSON：" + rootMessage(e));
        }
    }

    /**
     * 校验（结构 + 图 + 表达式走通性）。
     *
     * <p>返回**全部**错误而不是第一条：画布上用户一次改五处，只回一条他要来回五次。
     */
    public static List<ValidationError> validate(DagDsl dsl) {
        return new GraphValidator(dsl).validate();
    }

    public static ScopeSet scopes(DagDsl dsl) {
        return Scopes.build(dsl.index());
    }

    public static String toJson(DagDsl dsl) {
        try {
            return MAPPER.writeValueAsString(dsl);
        } catch (Exception e) {
            throw new IllegalArgumentException("流程 DSL 序列化失败：" + rootMessage(e));
        }
    }

    private static String rootMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
