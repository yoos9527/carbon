package com.carbon.framework.dag.registry;

import com.carbon.framework.dag.compiler.CompiledFlow;
import com.carbon.framework.dag.dsl.NodeType;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.cmp.CallCmp;
import com.carbon.framework.dag.cmp.CondCmp;
import com.carbon.framework.dag.cmp.DelayCmp;
import com.carbon.framework.dag.cmp.EndCmp;
import com.carbon.framework.dag.cmp.ForeachCmp;
import com.carbon.framework.dag.cmp.LoopCmp;
import com.carbon.framework.dag.cmp.ScriptCmp;
import com.carbon.framework.dag.cmp.StartCmp;
import com.yomahub.liteflow.builder.LiteFlowNodeBuilder;
import com.yomahub.liteflow.builder.el.LiteFlowChainELBuilder;
import com.yomahub.liteflow.common.entity.ValidationResp;
import com.yomahub.liteflow.flow.FlowBus;
import com.yomahub.liteflow.meta.LiteflowMetaOperator;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@link CompiledFlow} 注册进 LiteFlow 的全局节点表与流程表。
 *
 * <p>三条不变量，代码里全部围绕它写：
 * <ol>
 *   <li><b>节点 id 全局唯一</b>：LiteFlow 的节点注册表是 JVM 级单例，节点 id 会串流程。
 *       所以 {@code ElCompiler} 必须带流程 key 前缀，同一个 elId 只注册一次；</li>
 *   <li><b>同 id 不复注册</b>：重复 build 会抛「节点已存在」，这里用 {@link #REGISTERED} 去重；</li>
 *   <li><b>改流程 = 重新注册 chain</b>：{@code LiteflowMetaOperator.reloadOneChain} 会平滑替换，
 *       正在跑的旧版本继续跑完（这也是「发布版本必须不可变」的运行时依据）。</li>
 * </ol>
 *
 * @author carbon
 */
public final class ChainRegistry {

    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private ChainRegistry() {
    }

    /**
     * EL 语法校验（不注册，只判语法与节点引用）。
     *
     * @param el EL 表达式
     * @return 通过返回 null，否则返回错误信息
     */
    public static String validateEl(String el) {
        try {
            ValidationResp response = LiteFlowChainELBuilder.validateWithEx(el);
            if (response != null && !response.isSuccess()) {
                Exception cause = response.getCause();
                return cause == null ? "EL 语法不正确" : cause.getMessage();
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * 注册节点与流程。
     *
     * @param flow    编译产物
     * @param chainId LiteFlow 流程 id，建议 {@code flow_{flowKey}_v{version}}
     */
    public static void register(CompiledFlow flow, String chainId) {
        for (Map.Entry<String, NodeDef> entry : flow.nodeDefs().entrySet()) {
            registerNode(entry.getKey(), entry.getValue());
        }
        LiteFlowChainELBuilder.createChain()
                .setChainId(chainId)
                .setEL(flow.el())
                .build();
    }

    /**
     * 热更新某个流程的 EL（节点集不变时用）。
     *
     * @param chainId 流程 id
     * @param el      新 EL
     */
    public static void reload(String chainId, String el) {
        LiteflowMetaOperator.reloadOneChain(chainId, el);
    }

    /**
     * 摘除流程（不清节点，节点注册表是共享的）。
     *
     * @param chainId 流程 id
     */
    public static void unregister(String chainId) {
        FlowBus.removeChain(chainId);
    }

    private static void registerNode(String elId, NodeDef node) {
        if (!REGISTERED.add(elId)) {
            return;
        }
        switch (node.type()) {
            case CONDITION -> LiteFlowNodeBuilder.createBooleanNode()
                    .setId(elId).setName(node.id()).setClazz(CondCmp.class).build();
            case LOOP -> LiteFlowNodeBuilder.createBooleanNode()
                    .setId(elId).setName(node.id()).setClazz(LoopCmp.class).build();
            case FOREACH -> LiteFlowNodeBuilder.createIteratorNode()
                    .setId(elId).setName(node.id()).setClazz(ForeachCmp.class).build();
            case START -> LiteFlowNodeBuilder.createCommonNode()
                    .setId(elId).setName(node.id()).setClazz(StartCmp.class).build();
            case END -> LiteFlowNodeBuilder.createCommonNode()
                    .setId(elId).setName(node.id()).setClazz(EndCmp.class).build();
            case CONNECTOR_CALL -> LiteFlowNodeBuilder.createCommonNode()
                    .setId(elId).setName(node.id()).setClazz(CallCmp.class).build();
            case SCRIPT -> LiteFlowNodeBuilder.createCommonNode()
                    .setId(elId).setName(node.id()).setClazz(ScriptCmp.class).build();
            case DELAY -> LiteFlowNodeBuilder.createCommonNode()
                    .setId(elId).setName(node.id()).setClazz(DelayCmp.class).build();
        }
    }
}
