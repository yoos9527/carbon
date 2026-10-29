package com.carbon.modules.partner.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.event.PartnerCredentialChangedEvent;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.encrypt.CryptoService;
import com.carbon.common.json.JsonUtils;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.partner.domain.bo.CredentialBo;
import com.carbon.modules.partner.domain.entity.PartnerCredential;
import com.carbon.modules.partner.domain.vo.CredentialVo;
import com.carbon.modules.partner.mapper.PartnerCredentialMapper;
import com.carbon.modules.partner.mapper.PartnerMapper;
import com.carbon.modules.partner.service.ICredentialService;
import com.carbon.modules.partner.support.CredentialConfigCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 凭证管理实现。
 *
 * <p>三条刻意的规则：
 * <ul>
 *   <li><b>默认凭证最多一个</b>：设默认时先把同合作方的其他默认清掉，
 *       否则「这次用的哪套 key」就变成按 id 排序的随机结果。</li>
 *   <li><b>停用/过期的凭证不静默降级</b>：显式指定的凭证不可用时直接报错，
 *       而不是「没 key 就不加鉴权头发出去」——后者会让三方返回一个莫名其妙的 401，
 *       排查方向全错。只有「合作方默认凭证」缺失才视为无鉴权。</li>
 *   <li><b>变更即发事件</b>：换 token 类凭证的 token 缓存在 engine 里，不发事件就要等最长 1 小时。</li>
 * </ul>
 *
 * @author carbon
 */
@Service
public class CredentialServiceImpl implements ICredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialServiceImpl.class);

    private final PartnerCredentialMapper credentialMapper;
    private final PartnerMapper partnerMapper;
    private final CryptoService crypto;
    private final ApplicationEventPublisher events;

    public CredentialServiceImpl(PartnerCredentialMapper credentialMapper,
                                 PartnerMapper partnerMapper,
                                 CryptoService crypto,
                                 ApplicationEventPublisher events) {
        this.credentialMapper = credentialMapper;
        this.partnerMapper = partnerMapper;
        this.crypto = crypto;
        this.events = events;
    }

    @Override
    public List<CredentialVo> list(Long partnerId) {
        requirePartner(partnerId);
        return credentialMapper.selectList(new LambdaQueryWrapper<PartnerCredential>()
                        .eq(PartnerCredential::getPartnerId, partnerId)
                        .orderByDesc(PartnerCredential::getIsDefault)
                        .orderByAsc(PartnerCredential::getCode))
                .stream().map(this::toVo).toList();
    }

    @Override
    public CredentialVo detail(Long partnerId, Long id) {
        return toVo(requireOwned(partnerId, id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long partnerId, CredentialBo bo) {
        requirePartner(partnerId);
        assertCodeAvailable(partnerId, bo.getCode(), null);

        PartnerCredential entity = new PartnerCredential();
        entity.setPartnerId(partnerId);
        applyTo(entity, bo, Map.of());
        entity.setIsDefault(0);
        credentialMapper.insert(entity);

        if (Boolean.TRUE.equals(bo.getIsDefault())) {
            setDefault(partnerId, entity.getId());
        }
        events.publishEvent(PartnerCredentialChangedEvent.of(entity.getId()));
        log.info("凭证创建成功 partnerId={} id={} code={} type={}", partnerId, entity.getId(),
                entity.getCode(), entity.getType());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long partnerId, Long id, CredentialBo bo) {
        PartnerCredential entity = requireOwned(partnerId, id);
        if (!entity.getCode().equals(bo.getCode())) {
            assertCodeAvailable(partnerId, bo.getCode(), id);
        }
        Map<String, Object> existingPlain = entity.getConfig() == null || entity.getConfig().isBlank()
                ? Map.of()
                : CredentialConfigCodec.decrypt(JsonUtils.parseMap(entity.getConfig()), crypto);

        String previousType = entity.getType();
        applyTo(entity, bo, existingPlain);
        credentialMapper.updateById(entity);

        if (Boolean.TRUE.equals(bo.getIsDefault()) && !Integer.valueOf(1).equals(entity.getIsDefault())) {
            setDefault(partnerId, id);
        }
        events.publishEvent(PartnerCredentialChangedEvent.of(id));
        // 类型换了（例如 api_key 改 oauth2_cc）时，旧类型的 token 缓存已经没有意义，
        // 但缓存 key 是 credentialId:type，类型变了自然不会再命中，无需额外处理。
        log.info("凭证更新成功 partnerId={} id={} type={} -> {}", partnerId, id, previousType, entity.getType());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long partnerId, Long id) {
        PartnerCredential entity = requireOwned(partnerId, id);
        credentialMapper.deleteById(entity.getId());
        events.publishEvent(PartnerCredentialChangedEvent.of(id));
        log.info("凭证删除成功 partnerId={} id={} code={}", partnerId, id, entity.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long partnerId, Long id) {
        PartnerCredential target = requireOwned(partnerId, id);
        List<PartnerCredential> siblings = credentialMapper.selectList(
                new LambdaQueryWrapper<PartnerCredential>().eq(PartnerCredential::getPartnerId, partnerId));
        for (PartnerCredential sibling : siblings) {
            int want = sibling.getId().equals(target.getId()) ? 1 : 0;
            if (!Integer.valueOf(want).equals(sibling.getIsDefault())) {
                sibling.setIsDefault(want);
                credentialMapper.updateById(sibling);
            }
        }
        events.publishEvent(PartnerCredentialChangedEvent.of(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copy(Long partnerId, Long id) {
        PartnerCredential source = requireOwned(partnerId, id);
        String code = nextCopyCode(partnerId, source.getCode());

        PartnerCredential target = new PartnerCredential();
        target.setPartnerId(partnerId);
        target.setCode(code);
        target.setName(truncate(source.getName() + " 副本", 128));
        target.setType(source.getType());
        // 直接搬密文不解密：同一部署下 CARBON_MASTER_KEY 是同一把，
        // 解密再加密只是多一次可能失败的机会。
        target.setConfig(source.getConfig());
        target.setIsDefault(0);
        // 副本一律停用：它拿着同一套密钥，建出来就能被调用等于「点一下就多了一个入口」
        target.setStatus(0);
        target.setRemark(source.getRemark());
        credentialMapper.insert(target);

        events.publishEvent(PartnerCredentialChangedEvent.of(target.getId()));
        return target.getId();
    }

    @Override
    public PartnerCredential requireById(Long id) {
        if (id == null) {
            throw ServiceException.of(BizError.CREDENTIAL_NOT_FOUND, "凭证不存在：id 为空");
        }
        PartnerCredential entity = credentialMapper.selectById(id);
        if (entity == null) {
            throw ServiceException.of(BizError.CREDENTIAL_NOT_FOUND, "凭证不存在：id=" + id);
        }
        return entity;
    }

    @Override
    public PartnerCredential requireOwned(Long partnerId, Long id) {
        PartnerCredential entity = requireById(id);
        if (partnerId != null && !partnerId.equals(entity.getPartnerId())) {
            throw ServiceException.of(BizError.CREDENTIAL_NOT_FOUND,
                    "凭证 " + id + " 不属于合作方 " + partnerId);
        }
        return entity;
    }

    @Override
    public PartnerCredential findDefault(Long partnerId) {
        if (partnerId == null) {
            return null;
        }
        List<PartnerCredential> candidates = credentialMapper.selectList(new LambdaQueryWrapper<PartnerCredential>()
                .eq(PartnerCredential::getPartnerId, partnerId)
                .eq(PartnerCredential::getIsDefault, 1)
                .eq(PartnerCredential::getStatus, 1)
                .orderByDesc(PartnerCredential::getUpdatedAt));
        for (PartnerCredential candidate : candidates) {
            if (inEffect(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public Map<String, Object> plainConfig(PartnerCredential credential) {
        if (credential == null || credential.getConfig() == null || credential.getConfig().isBlank()) {
            return Map.of();
        }
        return CredentialConfigCodec.decrypt(JsonUtils.parseMap(credential.getConfig()), crypto);
    }

    @Override
    public List<PartnerCredential> listByPartner(Long partnerId) {
        return credentialMapper.selectList(new LambdaQueryWrapper<PartnerCredential>()
                .eq(PartnerCredential::getPartnerId, partnerId));
    }

    @Override
    public Resolved resolve(Connector endpoint, ConnectorOperation operation) {
        String mode = operation.getCredentialMode() == null || operation.getCredentialMode().isBlank()
                ? "INHERIT" : operation.getCredentialMode().trim().toUpperCase();

        if ("NONE".equals(mode)) {
            return Resolved.NONE;
        }
        if ("OVERRIDE".equals(mode)) {
            return toResolved(requireUsable(operation.getCredentialId(), endpoint.getPartnerId(), "接口指定的"));
        }
        if (endpoint.getCredentialId() != null) {
            return toResolved(requireUsable(endpoint.getCredentialId(), endpoint.getPartnerId(), "接入点指定的"));
        }
        PartnerCredential fallback = findDefault(endpoint.getPartnerId());
        return fallback == null ? Resolved.NONE : toResolved(fallback);
    }

    // ------------------------------------------------------------------ 内部方法

    private Resolved toResolved(PartnerCredential credential) {
        return new Resolved(credential.getId(), credential.getType(), plainConfig(credential));
    }

    /**
     * 显式指定的凭证必须可用：存在、同属一个合作方、已启用、在生效期内。
     *
     * <p>这里不做「静默降级成不鉴权」——那样三方会回一个 401，而错误信息里全是三方的内容，
     * 没人能想到根因是「平台悄悄把 key 去掉了」。
     */
    private PartnerCredential requireUsable(Long id, Long partnerId, String source) {
        if (id == null) {
            throw ServiceException.param(source + "凭证未指定，请在接口或接入点上选择一条凭证，或把接口凭证模式改为继承");
        }
        PartnerCredential credential = requireById(id);
        if (partnerId != null && !partnerId.equals(credential.getPartnerId())) {
            throw ServiceException.param(source + "凭证 " + id + " 不属于当前合作方，跨合作方引用密钥是不允许的");
        }
        if (!Integer.valueOf(1).equals(credential.getStatus())) {
            throw ServiceException.param(source + "凭证已停用：" + credential.getName());
        }
        if (!inEffect(credential)) {
            throw ServiceException.param(source + "凭证不在生效期内：" + credential.getName());
        }
        return credential;
    }

    private boolean inEffect(PartnerCredential credential) {
        LocalDateTime now = LocalDateTime.now();
        if (credential.getEffectiveFrom() != null && credential.getEffectiveFrom().isAfter(now)) {
            return false;
        }
        return credential.getEffectiveTo() == null || !credential.getEffectiveTo().isBefore(now);
    }

    private void requirePartner(Long partnerId) {
        if (partnerId == null || partnerMapper.selectById(partnerId) == null) {
            throw ServiceException.of(BizError.PARTNER_NOT_FOUND, "合作方不存在：id=" + partnerId);
        }
    }

    private void assertCodeAvailable(Long partnerId, String code, Long excludeId) {
        LambdaQueryWrapper<PartnerCredential> wrapper = new LambdaQueryWrapper<PartnerCredential>()
                .eq(PartnerCredential::getPartnerId, partnerId)
                .eq(PartnerCredential::getCode, code);
        if (excludeId != null) {
            wrapper.ne(PartnerCredential::getId, excludeId);
        }
        if (credentialMapper.exists(wrapper)) {
            throw ServiceException.param("该合作方下编码已被占用：" + code);
        }
    }

    private String nextCopyCode(Long partnerId, String base) {
        for (int i = 1; i <= 99; i++) {
            String suffix = i == 1 ? "-copy" : "-copy" + i;
            String candidate = truncate(base, 64 - suffix.length()) + suffix;
            boolean taken = credentialMapper.exists(new LambdaQueryWrapper<PartnerCredential>()
                    .eq(PartnerCredential::getPartnerId, partnerId)
                    .eq(PartnerCredential::getCode, candidate));
            if (!taken) {
                return candidate;
            }
        }
        throw ServiceException.param("复制失败：从 " + base + " 派生的编码都被占用了，请直接新建");
    }

    private void applyTo(PartnerCredential entity, CredentialBo bo, Map<String, Object> existingPlain) {
        entity.setCode(bo.getCode().trim());
        entity.setName(bo.getName().trim());
        entity.setType(bo.getType());
        Map<String, Object> encrypted = CredentialConfigCodec.encryptForSave(
                bo.getType(), bo.getConfig(), existingPlain, crypto);
        entity.setConfig(JsonUtils.toJson(encrypted));
        entity.setStatus(bo.getStatus() == null ? 1 : bo.getStatus());
        entity.setEffectiveFrom(bo.getEffectiveFrom());
        entity.setEffectiveTo(bo.getEffectiveTo());
        entity.setRemark(bo.getRemark());
    }

    private CredentialVo toVo(PartnerCredential entity) {
        CredentialVo vo = CredentialVo.of(entity);
        vo.setConfigMasked(CredentialConfigCodec.mask(entity.getType(),
                entity.getConfig() == null ? Map.of() : JsonUtils.parseMap(entity.getConfig())));
        return vo;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
