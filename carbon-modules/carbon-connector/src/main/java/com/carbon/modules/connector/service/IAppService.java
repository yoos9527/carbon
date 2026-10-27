package com.carbon.modules.connector.service;

import com.carbon.modules.connector.domain.entity.App;

import java.util.List;
import java.util.Map;

/**
 * 调用方应用（网关凭证）。
 *
 * <p>密钥的生命周期只有三步：创建时**明文返回一次**、库里只存密文、之后任何接口都只返回掩码。
 * 这决定了三个方法的形状——{@link #create} 的返回值必须带上 secret，
 * 其余方法不可能带上，所以它们的返回类型故意不同。
 *
 * @author carbon
 */
public interface IAppService {

    List<Map<String, Object>> list();

    /**
     * 创建应用。
     *
     * @return {@code appKey} 与**一次性明文** {@code appSecret}
     */
    Map<String, Object> create(String name, List<Long> allowedConnectors, Integer rateLimitQps,
                               List<String> ipWhitelist);

    /**
     * 重新生成密钥（旧密钥立即失效）。
     *
     * @return 一次性明文。
     */
    Map<String, Object> rotateSecret(Long id);

    /**
     * 更新应用的可编辑字段：名称、连接器白名单、QPS、IP 白名单。
     *
     * <p>**改不了 appKey**：它是调用方已经写进配置、监控、日志检索里的东西，
     * 平台侧「顺手」换一个只会让接入方半夜排查 401。换 key 的正确做法是新建应用 + 停用旧的。
     * 也**改不了 secret**——那是 {@link #rotateSecret} 的事，且只在生成时明文返回一次。
     *
     * @param allowedConnectors {@code null}/空 = 不限制（语义同创建时）
     * @param ipWhitelist       {@code null}/空 = 不限制；元素须是 IP 或 CIDR
     */
    void update(Long id, String name, List<Long> allowedConnectors, Integer rateLimitQps,
                List<String> ipWhitelist);

    void updateStatus(Long id, Integer status);

    void delete(Long id);

    /**
     * 网关鉴权入口：按 appKey 取应用并返回**明文** secret。
     *
     * @return {@code null} 表示 appKey 不存在或已停用——网关要统一回 {@code SIGN_INVALID}，
     *         不能区分「不存在」与「已停用」，否则等于给攻击者一个枚举工具。
     */
    AppCredential findByAppKey(String appKey);

    /**
     * 明文凭证。
     *
     * @param app     应用实体
     * @param secret  明文密钥
     * @author carbon
     */
    record AppCredential(App app, String secret) {
    }
}
