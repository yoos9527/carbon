package com.carbon.common.core.event;

/**
 * 合作方凭证变更（新建 / 修改 / 删除 / 设为默认）时发出。
 *
 * <p>换 token 类凭证（{@code oauth2_cc} / {@code login_token}）的 token 缓存在 engine 里，
 * 而凭证的增删改发生在 {@code carbon-connector}。让 connector 直接调 engine 会形成
 * 叶子模块反向依赖（见 01-总体架构 §9.3），所以把事件类放在 {@code common-core}：
 * 发布方与监听方各自可见，依赖方向不变。
 *
 * <p>不发这个事件的后果是可预期的：改完密钥点了保存，运行时还拿着旧 token 直到过期（最长 1 小时），
 * 「我明明改了啊」就成了一条查不出原因的故障。
 *
 * @param credentialId 凭证 id
 * @author carbon
 */
public record PartnerCredentialChangedEvent(Long credentialId) {

    public static PartnerCredentialChangedEvent of(Long credentialId) {
        return new PartnerCredentialChangedEvent(credentialId);
    }
}
