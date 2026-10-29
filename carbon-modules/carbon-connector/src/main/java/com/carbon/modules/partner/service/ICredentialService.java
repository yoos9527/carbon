package com.carbon.modules.partner.service;

import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.partner.domain.bo.CredentialBo;
import com.carbon.modules.partner.domain.entity.PartnerCredential;
import com.carbon.modules.partner.domain.vo.CredentialVo;

import java.util.List;
import java.util.Map;

/**
 * 凭证管理。
 *
 * <p>除了 CRUD，还有一个 {@link #resolve} ——「这次调用到底用哪套 key」的唯一判定点。
 * 判定必须只有一处：分散到试调、网关、编排里各写一遍，迟早出现「试调通、网关不通」。
 *
 * @author carbon
 */
public interface ICredentialService {

    List<CredentialVo> list(Long partnerId);

    CredentialVo detail(Long partnerId, Long id);

    Long create(Long partnerId, CredentialBo bo);

    void update(Long partnerId, Long id, CredentialBo bo);

    void delete(Long partnerId, Long id);

    /** 设为该合作方的默认凭证（自动取消其他凭证的默认标记） */
    void setDefault(Long partnerId, Long id);

    /** 复制一份凭证（密文直接搬，不解密），副本默认停用 */
    Long copy(Long partnerId, Long id);

    PartnerCredential requireById(Long id);

    PartnerCredential requireOwned(Long partnerId, Long id);

    /** 合作方的默认启用凭证；没有则返回 {@code null} */
    PartnerCredential findDefault(Long partnerId);

    /** 库里的配置转明文（解密 {@code ENC:v1:}），供脚本与模板使用 */
    Map<String, Object> plainConfig(PartnerCredential credential);

    List<PartnerCredential> listByPartner(Long partnerId);

    /**
     * 解析一次调用实际使用的凭证。
     *
     * <p>顺序（与产品口径一致）：
     * 接口 {@code NONE} → 不鉴权；接口 {@code OVERRIDE} → 用接口指定的凭证；
     * 接口 {@code INHERIT} → 接入点的 {@code credential_id}；再没有 → 合作方的默认凭证。
     * 一路都没有 = 不鉴权（有些三方就是开放接口）。
     *
     * @throws com.carbon.common.core.exception.ServiceException 显式指定的凭证已停用/过期/不属于同一合作方
     */
    Resolved resolve(Connector endpoint, ConnectorOperation operation);

    /**
     * 解析结果。
     *
     * @param id     凭证 id，无鉴权时为 {@code null}
     * @param type   凭证类型，无鉴权时为 {@code none}
     * @param config 明文配置，可直接给脚本与模板用
     */
    record Resolved(Long id, String type, Map<String, Object> config) {

        public static final Resolved NONE = new Resolved(null, "none", Map.of());

        public boolean authenticated() {
            return id != null;
        }
    }
}
