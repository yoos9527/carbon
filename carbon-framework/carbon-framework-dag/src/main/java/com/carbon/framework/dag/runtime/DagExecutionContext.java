package com.carbon.framework.dag.runtime;

import com.carbon.framework.dag.dsl.node.NodeDef;

import java.util.Map;

/**
 * 组件执行期能拿到的运行时句柄。
 *
 * <p>这是 {@code carbon-framework-dag} 与 {@code carbon-engine} 之间唯一的运行期契约：
 * 框架只知道自己要一个「变量池 + 服务查找 + 节点定义反查」，不知道连接器、HTTP、脚本这些事；
 * 引擎负责实现本接口（见 §9 依赖方向约束）。
 *
 * <p>用接口而不是静态注册表：同一个 JVM 里可以同时跑多个流程实例、单测里可以随便造，
 * 不会出现「静态状态被上一个用例污染」这种最难查的测试问题。
 *
 * @author carbon
 */
public interface DagExecutionContext {

    /**
     * @return 本次流程运行 id（{@code flow_run.id} / traceId）
     */
    String flowRunId();

    /**
     * 通过 EL 节点 id 反查 DSL 节点定义。
     *
     * @param elId EL 节点 id
     * @return 节点定义，未知返回 null
     */
    NodeDef nodeDef(String elId);

    /**
     * @return 变量池，组件可直接读写（{@code vars} 语义，跨节点共享）
     */
    Map<String, Object> vars();

    /**
     * 绑定 FOREACH 当前元素。
     *
     * @param itemVar 变量名
     * @param item    当前元素
     */
    void bindItem(String itemVar, Object item);

    /**
     * 取引擎侧服务（如 HTTP 执行器、脚本执行器、日志写入器）。
     *
     * @param type 服务类型
     * @param <T>  服务类型
     * @return 服务实例
     * @throws IllegalStateException 未注册
     */
    <T> T service(Class<T> type);
}
