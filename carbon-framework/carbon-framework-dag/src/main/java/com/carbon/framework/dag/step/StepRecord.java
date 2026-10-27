package com.carbon.framework.dag.step;

/**
 * 一条节点执行记录（{@code flow_step_run} 的一行）。
 *
 * @param elId         EL 节点 id
 * @param dslId        DSL 节点 id（画布上的那个）
 * @param name         节点名（画布展示名）
 * @param tag          LiteFlow tag
 * @param success      是否成功
 * @param elapsedMs    耗时毫秒
 * @param loopIndex    循环轮次（LiteFlow 提供，非循环为 null）
 * @param errorMessage 失败原因
 * @author carbon
 */
public record StepRecord(String elId, String dslId, String name, String tag, boolean success,
                         long elapsedMs, Integer loopIndex, String errorMessage) {
}
