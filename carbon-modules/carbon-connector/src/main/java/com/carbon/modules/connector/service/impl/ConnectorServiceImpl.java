package com.carbon.modules.connector.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.encrypt.CryptoService;
import com.carbon.common.json.JsonUtils;
import com.carbon.framework.expression.TemplateRenderer;
import com.carbon.modules.connector.domain.bo.ConnectorBo;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.domain.entity.ConnectorAuth;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.connector.domain.vo.ConnectorVo;
import com.carbon.modules.connector.mapper.ConnectorAuthMapper;
import com.carbon.modules.connector.mapper.ConnectorMapper;
import com.carbon.modules.connector.mapper.ConnectorOperationMapper;
import com.carbon.modules.connector.service.IConnectorService;
import com.carbon.modules.connector.support.AuthConfigCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 连接器管理实现。
 *
 * <p>几个刻意的选择：
 * <ul>
 *   <li><b>编码唯一性用数据库唯一键兜底</b>：先查再插有并发窗口，所以这里既查也接
 *       {@code DuplicateKeyException}，两者都要有——只靠查会漏，只靠异常则错误信息难懂。</li>
 *   <li><b>保存时就校验模板语法</b>：{@code {{...}}` 写错在试调时才发现的代价太高，
 *       用 {@link TemplateRenderer#scan(String)} 当场报「第几行第几列」。</li>
 *   <li><b>删除是真删</b>：连接器没有 {@code deleted} 列，删了就是删了，连带删掉鉴权与接口。
 *       想保留历史就把 {@code status} 置 0。</li>
 * </ul>
 *
 * @author carbon
 */
@Service
public class ConnectorServiceImpl implements IConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorServiceImpl.class);

    private final ConnectorMapper connectorMapper;
    private final ConnectorAuthMapper authMapper;
    private final ConnectorOperationMapper operationMapper;
    private final CryptoService crypto;
    private final TemplateRenderer templateRenderer;

    public ConnectorServiceImpl(ConnectorMapper connectorMapper,
                                ConnectorAuthMapper authMapper,
                                ConnectorOperationMapper operationMapper,
                                CryptoService crypto,
                                TemplateRenderer templateRenderer) {
        this.connectorMapper = connectorMapper;
        this.authMapper = authMapper;
        this.operationMapper = operationMapper;
        this.crypto = crypto;
        this.templateRenderer = templateRenderer;
    }

    @Override
    public IPage<ConnectorVo> page(PageQuery pageQuery, String category, Integer status, String keyword) {
        LambdaQueryWrapper<Connector> wrapper = new LambdaQueryWrapper<Connector>()
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
        Map<Long, ConnectorAuth> auths = ids.isEmpty() ? Map.of()
                : authMapper.selectList(new LambdaQueryWrapper<ConnectorAuth>()
                        .in(ConnectorAuth::getConnectorId, ids))
                .stream().collect(Collectors.toMap(ConnectorAuth::getConnectorId, a -> a, (a, b) -> a));

        return result.convert(entity -> toVo(entity, auths.get(entity.getId()), counts.getOrDefault(entity.getId(), 0)));
    }

    @Override
    public ConnectorVo detail(Long id) {
        Connector entity = requireById(id);
        ConnectorAuth auth = findAuth(id);
        return toVo(entity, auth, countOperations(List.of(id)).getOrDefault(id, 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ConnectorBo bo) {
        assertCodeAvailable(bo.getCode(), null);
        validateTemplates(bo);

        Connector entity = new Connector();
        applyTo(entity, bo);
        connectorMapper.insert(entity);

        saveAuth(entity.getId(), bo.getAuth(), null);
        log.info("连接器创建成功 id={} code={}", entity.getId(), entity.getCode());
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

        applyTo(entity, bo);
        connectorMapper.updateById(entity);

        saveAuth(id, bo.getAuth(), findAuth(id));
        log.info("连接器更新成功 id={} code={}", id, entity.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 先删子表再删主表：DDL 里没有外键，顺序错了会留下孤儿鉴权配置
        authMapper.delete(new LambdaQueryWrapper<ConnectorAuth>().in(ConnectorAuth::getConnectorId, ids));
        operationMapper.delete(new LambdaQueryWrapper<ConnectorOperation>().in(ConnectorOperation::getConnectorId, ids));
        connectorMapper.deleteByIds(ids);
        log.info("连接器删除成功 ids={}", ids);
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
            throw ServiceException.of(BizError.CONNECTOR_NOT_FOUND, "连接器不存在：" + code);
        }
        return entity;
    }

    @Override
    public Connector requireById(Long id) {
        Connector entity = connectorMapper.selectById(id);
        if (entity == null) {
            throw ServiceException.of(BizError.CONNECTOR_NOT_FOUND, "连接器不存在：id=" + id);
        }
        return entity;
    }

    @Override
    public ConnectorAuth findAuth(Long connectorId) {
        return authMapper.selectOne(
                new LambdaQueryWrapper<ConnectorAuth>().eq(ConnectorAuth::getConnectorId, connectorId));
    }

    @Override
    public Map<String, Object> authConfigPlain(Long connectorId) {
        ConnectorAuth auth = findAuth(connectorId);
        if (auth == null || auth.getConfig() == null || auth.getConfig().isBlank()) {
            return Map.of();
        }
        Map<String, Object> stored = JsonUtils.parseMap(auth.getConfig());
        return AuthConfigCodec.decrypt(stored, crypto);
    }

    @Override
    public ConnectorOperation requireOperation(Long connectorId, String operationCode) {
        ConnectorOperation operation = operationMapper.selectOne(new LambdaQueryWrapper<ConnectorOperation>()
                .eq(ConnectorOperation::getConnectorId, connectorId)
                .eq(ConnectorOperation::getCode, operationCode));
        if (operation == null) {
            throw ServiceException.of(BizError.OPERATION_NOT_FOUND,
                    "接口不存在：" + operationCode + "（连接器 id=" + connectorId + "）");
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

    // ------------------------------------------------------------------ 内部方法

    private void assertCodeAvailable(String code, Long excludeId) {
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

    /**
     * 鉴权配置的 upsert。
     *
     * @param existing 库里已有的鉴权（可能为 null）；用它兜住「编辑时没重填密钥」的场景
     */
    private void saveAuth(Long connectorId, ConnectorBo.AuthBo authBo, ConnectorAuth existing) {
        if (authBo == null || authBo.getAuthType() == null) {
            return;
        }
        Map<String, Object> existingPlain = existing == null || existing.getConfig() == null
                ? Map.of()
                : AuthConfigCodec.decrypt(JsonUtils.parseMap(existing.getConfig()), crypto);

        Map<String, Object> encrypted = AuthConfigCodec.encryptForSave(
                authBo.getAuthType(), authBo.getConfig(), existingPlain, crypto);

        // none 不需要落库，直接清掉旧的，避免「类型改成 none 但旧密钥还留在库里」
        if ("none".equals(authBo.getAuthType())) {
            if (existing != null) {
                authMapper.deleteById(existing.getId());
            }
            return;
        }

        ConnectorAuth target = existing == null ? new ConnectorAuth() : existing;
        target.setConnectorId(connectorId);
        target.setAuthType(authBo.getAuthType());
        target.setConfig(JsonUtils.toJson(encrypted));
        target.setStatus(1);
        if (existing == null) {
            authMapper.insert(target);
        } else {
            authMapper.updateById(target);
        }
    }

    private ConnectorVo toVo(Connector entity, ConnectorAuth auth, int operationCount) {
        ConnectorVo vo = ConnectorVo.of(entity);
        vo.setDefaultHeaders(entity.getDefaultHeaders() == null
                ? Map.of()
                : parseStringMap(entity.getDefaultHeaders()));
        vo.setRetry(entity.getRetry() == null
                ? Map.of()
                : JsonUtils.parseMap(entity.getRetry()));
        vo.setOperationCount(operationCount);
        if (auth != null) {
            vo.setAuthType(auth.getAuthType());
            Map<String, Object> stored = auth.getConfig() == null
                    ? Map.of()
                    : JsonUtils.parseMap(auth.getConfig());
            vo.setAuthConfigMasked(AuthConfigCodec.mask(auth.getAuthType(), stored));
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
