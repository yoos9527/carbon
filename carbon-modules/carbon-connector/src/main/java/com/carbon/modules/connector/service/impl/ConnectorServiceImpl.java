package com.carbon.modules.connector.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.json.JsonUtils;
import com.carbon.framework.expression.TemplateRenderer;
import com.carbon.modules.connector.domain.bo.ConnectorBo;
import com.carbon.modules.connector.domain.bo.ConnectorCopyBo;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.connector.domain.vo.ConnectorVo;
import com.carbon.modules.connector.mapper.ConnectorMapper;
import com.carbon.modules.connector.mapper.ConnectorOperationMapper;
import com.carbon.modules.connector.service.IConnectorService;
import com.carbon.modules.partner.domain.entity.Partner;
import com.carbon.modules.partner.domain.entity.PartnerCredential;
import com.carbon.modules.partner.mapper.PartnerCredentialMapper;
import com.carbon.modules.partner.mapper.PartnerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 接入点管理实现。
 *
 * <p>几个刻意的选择：
 * <ul>
 *   <li><b>编码唯一性用数据库唯一键兜底</b>：先查再插有并发窗口，所以这里既查也接
 *       {@code DuplicateKeyException}，两者都要有——只靠查会漏，只靠异常则错误信息难懂。</li>
 *   <li><b>保存时就校验模板语法</b>：{@code {{...}}} 写错在试调时才发现的代价太高，
 *       用 {@link TemplateRenderer#scan(String)} 当场报「第几行第几列」。</li>
 *   <li><b>保存时不校验三方连通性</b>：那属于试调的事，让保存按钮保持同步、可预期。</li>
 *   <li><b>凭证只校验归属，不校验启停</b>：允许先把接入点配好、凭证稍后再启用；
 *       真正调用时由 {@code CredentialServiceImpl.resolve} 把关并给出「凭证已停用」。</li>
 * </ul>
 *
 * @author carbon
 */
@Service
public class ConnectorServiceImpl implements IConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorServiceImpl.class);

    /** 网关已占用的首段路径字面量，接入点编码不能与之同义 */
    private static final List<String> RESERVED_GATEWAY_CODES = List.of("flows", "health");

    private final ConnectorMapper connectorMapper;
    private final ConnectorOperationMapper operationMapper;
    private final PartnerMapper partnerMapper;
    private final PartnerCredentialMapper credentialMapper;
    private final TemplateRenderer templateRenderer;

    public ConnectorServiceImpl(ConnectorMapper connectorMapper,
                               ConnectorOperationMapper operationMapper,
                               PartnerMapper partnerMapper,
                               PartnerCredentialMapper credentialMapper,
                               TemplateRenderer templateRenderer) {
        this.connectorMapper = connectorMapper;
        this.operationMapper = operationMapper;
        this.partnerMapper = partnerMapper;
        this.credentialMapper = credentialMapper;
        this.templateRenderer = templateRenderer;
    }

    @Override
    public IPage<ConnectorVo> page(PageQuery pageQuery, Long partnerId, String category,
                                   Integer status, String keyword) {
        LambdaQueryWrapper<Connector> wrapper = new LambdaQueryWrapper<Connector>()
                .eq(partnerId != null, Connector::getPartnerId, partnerId)
                .eq(category != null && !category.isBlank(), Connector::getCategory, category)
                .eq(status != null, Connector::getStatus, status)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Connector::getName, keyword)
                        .or().like(Connector::getCode, keyword))
                .orderByDesc(Connector::getUpdatedAt);

        Page<Connector> page = new Page<>(pageQuery.safePageNum(), pageQuery.safePageSize());
        IPage<Connector> result = connectorMapper.selectPage(page, wrapper);

        List<Long> ids = result.getRecords().stream().map(Connector::getId).toList();
        Map<Long, Integer> counts = countOperations(ids);
        Map<Long, Partner> partners = loadPartners(result.getRecords());
        Map<Long, PartnerCredential> credentials = loadCredentials(result.getRecords());

        return result.convert(entity -> toVo(entity, partners, credentials,
                counts.getOrDefault(entity.getId(), 0)));
    }

    @Override
    public ConnectorVo detail(Long id) {
        Connector entity = requireById(id);
        return toVo(entity, loadPartners(List.of(entity)), loadCredentials(List.of(entity)),
                countOperations(List.of(id)).getOrDefault(id, 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ConnectorBo bo) {
        assertCodeAvailable(bo.getCode(), null);
        validateTemplates(bo);
        assertPartnerAndCredential(bo);

        Connector entity = new Connector();
        applyTo(entity, bo);
        connectorMapper.insert(entity);
        log.info("接入点创建成功 id={} code={}", entity.getId(), entity.getCode());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ConnectorBo bo) {
        Connector entity = requireById(id);
        if (!entity.getCode().equals(bo.getCode())) {
            assertCodeAvailable(bo.getCode(), id);
        }
        validateTemplates(bo);
        assertPartnerAndCredential(bo);

        applyTo(entity, bo);
        connectorMapper.updateById(entity);
        log.info("接入点更新成功 id={} code={}", id, entity.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copy(Long id, ConnectorCopyBo bo) {
        Connector source = requireById(id);
        ConnectorCopyBo req = bo == null ? new ConnectorCopyBo() : bo;

        String code = req.getCode() == null || req.getCode().isBlank()
                ? nextCopyCode(source.getCode())
                : req.getCode().trim().toLowerCase();
        assertCodeAvailable(code, null);

        Connector target = new Connector();
        target.setCode(code);
        target.setName(req.getName() == null || req.getName().isBlank()
                ? truncate(source.getName() + " 副本", 64) : req.getName().trim());
        target.setPartnerId(source.getPartnerId());
        target.setCredentialId(source.getCredentialId());
        target.setCategory(source.getCategory());
        target.setBaseUrl(source.getBaseUrl());
        target.setDefaultHeaders(source.getDefaultHeaders());
        target.setTimeoutMs(source.getTimeoutMs());
        target.setRetry(source.getRetry());
        // 副本一律以停用状态落地：它指向同一个三方地址，
        // 一创建就能被网关调到，等于「复制一下就多了一个生产入口」。
        target.setStatus(0);
        target.setRemark(source.getRemark());
        connectorMapper.insert(target);

        int copied = 0;
        if (req.copyOperations()) {
            List<ConnectorOperation> operations = operationMapper.selectList(
                    new LambdaQueryWrapper<ConnectorOperation>()
                            .eq(ConnectorOperation::getConnectorId, id)
                            .orderByAsc(ConnectorOperation::getCreatedAt));
            for (ConnectorOperation source2 : operations) {
                ConnectorOperation op = new ConnectorOperation();
                op.setConnectorId(target.getId());
                op.setCode(source2.getCode());
                op.setName(source2.getName());
                op.setMethod(source2.getMethod());
                op.setPath(source2.getPath());
                op.setQueryParams(source2.getQueryParams());
                op.setHeaders(source2.getHeaders());
                op.setBodyType(source2.getBodyType());
                op.setBodyTemplate(source2.getBodyTemplate());
                op.setResponseExtract(source2.getResponseExtract());
                op.setPreScript(source2.getPreScript());
                op.setPostScript(source2.getPostScript());
                op.setScriptTimeoutMs(source2.getScriptTimeoutMs());
                op.setTimeoutMs(source2.getTimeoutMs());
                op.setRetry(source2.getRetry());
                op.setCredentialMode(source2.getCredentialMode());
                op.setCredentialId(source2.getCredentialId());
                op.setStatus(source2.getStatus());
                operationMapper.insert(op);
                copied++;
            }
        }
        log.info("接入点复制成功 {} -> {}（接口 {} 个）", source.getCode(), code, copied);
        return target.getId();
    }

    /**
     * 推导一个可用的副本编码：`-copy`、`-copy2`、`-copy3`…
     *
     * <p>后缀算在 64 位长度限制内，所以先把原编码裁到放得下后缀的长度——
     * 否则一个 64 位编码的接入点永远复制不了（拼接后超长，报的错还很难懂）。
     */
    private String nextCopyCode(String base) {
        for (int i = 1; i <= 99; i++) {
            String suffix = i == 1 ? "-copy" : "-copy" + i;
            String candidate = truncate(base, 64 - suffix.length()) + suffix;
            if (candidate.length() < 2) {
                candidate = "copy" + suffix;
            }
            if (RESERVED_GATEWAY_CODES.contains(candidate)) {
                continue;
            }
            if (!connectorMapper.exists(new LambdaQueryWrapper<Connector>().eq(Connector::getCode, candidate))) {
                return candidate;
            }
        }
        throw ServiceException.param("复制失败：从 " + base + " 派生的编码都被占用了，请直接指定 code");
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 先删子表再删主表：DDL 里没有外键，顺序错了会留下孤儿接口
        operationMapper.delete(new LambdaQueryWrapper<ConnectorOperation>()
                .in(ConnectorOperation::getConnectorId, ids));
        connectorMapper.deleteByIds(ids);
        log.info("接入点删除成功 ids={}", ids);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        Connector entity = requireById(id);
        entity.setStatus(status);
        connectorMapper.updateById(entity);
    }

    @Override
    public Connector requireByCode(String code) {
        Connector entity = connectorMapper.selectOne(
                new LambdaQueryWrapper<Connector>().eq(Connector::getCode, code));
        if (entity == null) {
            throw ServiceException.of(BizError.CONNECTOR_NOT_FOUND, "接入点不存在：" + code);
        }
        return entity;
    }

    @Override
    public Connector requireById(Long id) {
        Connector entity = connectorMapper.selectById(id);
        if (entity == null) {
            throw ServiceException.of(BizError.CONNECTOR_NOT_FOUND, "接入点不存在：id=" + id);
        }
        return entity;
    }

    @Override
    public ConnectorOperation requireOperation(Long connectorId, String operationCode) {
        ConnectorOperation operation = operationMapper.selectOne(new LambdaQueryWrapper<ConnectorOperation>()
                .eq(ConnectorOperation::getConnectorId, connectorId)
                .eq(ConnectorOperation::getCode, operationCode));
        if (operation == null) {
            throw ServiceException.of(BizError.OPERATION_NOT_FOUND,
                    "接口不存在：" + operationCode + "（接入点 id=" + connectorId + "）");
        }
        return operation;
    }

    @Override
    public Map<Long, Integer> countOperations(List<Long> connectorIds) {
        if (connectorIds == null || connectorIds.isEmpty()) {
            return Map.of();
        }
        List<ConnectorOperation> all = operationMapper.selectList(new LambdaQueryWrapper<ConnectorOperation>()
                .select(ConnectorOperation::getConnectorId)
                .in(ConnectorOperation::getConnectorId, connectorIds));
        Map<Long, Integer> counts = new HashMap<>();
        all.forEach(op -> counts.merge(op.getConnectorId(), 1, Integer::sum));
        return counts;
    }

    @Override
    public List<Connector> listEnabled() {
        return connectorMapper.selectList(new LambdaQueryWrapper<Connector>()
                .eq(Connector::getStatus, 1)
                .orderByAsc(Connector::getCode));
    }

    // ------------------------------------------------------------------ 内部方法

    /**
     * 合作方必须存在；凭证若指定了，必须存在且属于同一个合作方。
     *
     * <p>跨合作方挂凭证是典型的「复制粘贴配错」：KV 表里看着都对，调用时才把 A 家的密钥
     * 发给 B 家的地址。在保存时就拒掉，比在调用日志里发现一条 401 便宜得多。
     */
    private void assertPartnerAndCredential(ConnectorBo bo) {
        Partner partner = partnerMapper.selectById(bo.getPartnerId());
        if (partner == null) {
            throw ServiceException.of(BizError.PARTNER_NOT_FOUND, "合作方不存在：id=" + bo.getPartnerId());
        }
        Long credentialId = bo.getCredentialId();
        if (credentialId == null) {
            return;
        }
        PartnerCredential credential = credentialMapper.selectById(credentialId);
        if (credential == null) {
            throw ServiceException.of(BizError.CREDENTIAL_NOT_FOUND, "凭证不存在：id=" + credentialId);
        }
        if (!credential.getPartnerId().equals(bo.getPartnerId())) {
            throw ServiceException.param("凭证不属于该合作方：凭证 id=" + credentialId
                    + "，接入点所属合作方 id=" + bo.getPartnerId());
        }
    }

    private void assertCodeAvailable(String code, Long excludeId) {
        // 网关有两个字面量路径段（/gw/flows/** 与 /gw/health），接入点编码叫这两个名字
        // 会被 Spring 的「字面量优先」规则抢走，接口永远调不到。
        // 报在保存时而不是等人踩坑后去猜「为什么 404」。
        if (RESERVED_GATEWAY_CODES.contains(code)) {
            throw ServiceException.param("编码不能用保留字：" + code.toLowerCase() + "（已被网关占用）");
        }
        LambdaQueryWrapper<Connector> wrapper = new LambdaQueryWrapper<Connector>()
                .eq(Connector::getCode, code);
        if (excludeId != null) {
            wrapper.ne(Connector::getId, excludeId);
        }
        if (connectorMapper.exists(wrapper)) {
            throw ServiceException.param("编码已被占用：" + code);
        }
    }

    /**
     * 保存前把模板扫一遍。
     *
     * <p>只校验语法（有没有配对的 {@code }}、引用形态对不对），不校验变量是否存在——
     * {@code {{env.host}}} 这类变量是运行时才确定的。
     */
    private void validateTemplates(ConnectorBo bo) {
        checkTemplate("baseUrl", bo.getBaseUrl());
        if (bo.getDefaultHeaders() != null) {
            bo.getDefaultHeaders().forEach((name, value) -> checkTemplate("defaultHeaders." + name, value));
        }
    }

    private void checkTemplate(String where, String template) {
        if (template == null || template.isBlank()) {
            return;
        }
        try {
            templateRenderer.scan(template);
        } catch (RuntimeException e) {
            throw ServiceException.param(where + " 模板语法错误：" + e.getMessage());
        }
    }

    private void applyTo(Connector entity, ConnectorBo bo) {
        entity.setPartnerId(bo.getPartnerId());
        entity.setCredentialId(bo.getCredentialId());
        entity.setCode(bo.getCode());
        entity.setName(bo.getName());
        entity.setCategory(bo.getCategory());
        entity.setBaseUrl(bo.getBaseUrl());
        entity.setDefaultHeaders(bo.getDefaultHeaders() == null ? null : JsonUtils.toJson(bo.getDefaultHeaders()));
        entity.setTimeoutMs(bo.getTimeoutMs() == null ? 10_000 : bo.getTimeoutMs());
        entity.setRetry(bo.getRetry() == null ? null : JsonUtils.toJson(bo.getRetry()));
        entity.setStatus(bo.getStatus() == null ? 0 : bo.getStatus());
        entity.setRemark(bo.getRemark());
    }

    private Map<Long, Partner> loadPartners(List<Connector> connectors) {
        List<Long> ids = connectors.stream().map(Connector::getPartnerId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return partnerMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(Partner::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, PartnerCredential> loadCredentials(List<Connector> connectors) {
        List<Long> ids = connectors.stream().map(Connector::getCredentialId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return credentialMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(PartnerCredential::getId, Function.identity(), (a, b) -> a));
    }

    private ConnectorVo toVo(Connector entity, Map<Long, Partner> partners,
                             Map<Long, PartnerCredential> credentials, int operationCount) {
        ConnectorVo vo = ConnectorVo.of(entity);
        vo.setDefaultHeaders(entity.getDefaultHeaders() == null
                ? Map.of()
                : parseStringMap(entity.getDefaultHeaders()));
        vo.setRetry(entity.getRetry() == null
                ? Map.of()
                : JsonUtils.parseMap(entity.getRetry()));
        vo.setOperationCount(operationCount);
        Partner partner = entity.getPartnerId() == null ? null : partners.get(entity.getPartnerId());
        if (partner != null) {
            vo.setPartnerName(partner.getName());
        }
        PartnerCredential credential = entity.getCredentialId() == null
                ? null : credentials.get(entity.getCredentialId());
        if (credential != null) {
            vo.setCredentialName(credential.getName());
        }
        return vo;
    }

    private Map<String, String> parseStringMap(String json) {
        Map<String, Object> raw = JsonUtils.parseMap(json);
        Map<String, String> result = new HashMap<>();
        raw.forEach((k, v) -> result.put(k, v == null ? null : String.valueOf(v)));
        return result;
    }
}
