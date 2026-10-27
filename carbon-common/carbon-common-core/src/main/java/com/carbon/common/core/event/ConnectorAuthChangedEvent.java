package com.carbon.common.core.event;

/**
 * 连接器鉴权变更（保存 / 删除）时发出。
 *
 * <p>为什么用事件而不是让 connector 直接调 engine：{@code carbon-connector} 是叶子业务模块，
 * 不得依赖 {@code carbon-engine}（见 01-总体架构 §9.3 分层），而 token 缓存住在 engine 里。
 * 事件类放在 {@code common-core}，发布方与监听方各自可见，依赖方向不变。
 *
 * <p>不发这个事件的后果是可预期的：改了密钥、点了保存，运行时还拿着旧 token 直到过期（最长 1 小时），
 * 于是「我明明改了啊」变成一条查不出原因的故障。
 *
 * @param connectorId 连接器 id
 * @author carbon
 */
public record ConnectorAuthChangedEvent(Long connectorId) {

    public static ConnectorAuthChangedEvent of(Long connectorId) {
        return new ConnectorAuthChangedEvent(connectorId);
    }
}
