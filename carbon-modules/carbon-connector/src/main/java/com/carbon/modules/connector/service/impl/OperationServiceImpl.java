package com.carbon.modules.connector.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.json.JsonUtils;
import com.carbon.framework.expression.TemplateRenderer;
import com.carbon.modules.connector.domain.bo.OperationBo;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.connector.domain.vo.OperationVo;
import com.carbon.modules.connector.mapper.ConnectorOperationMapper;
import com.carbon.modules.connector.service.IConnectorService;
import com.carbon.modules.connector.service.IOperationService;
import com.carbon.modules.partner.domain.entity.PartnerCredential;
import com.carbon.modules.partner.mapper.PartnerCredentialMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 接口管理实现。
 *
 * <p>保存前做三件事，都是为了把错误从「运行期」提前到「保存点」：
 * <ol>
 *   <li>模板语法扫描（{@code {{...}}} 是否配对、引用形态是否合法）；</li>
 *   <li>{@code bodyType=json} 时做**结构性** JSON 校验——把所有占位符替换成 {@code 0} 后解析一次，
 *       能抓出漏逗号、少括号这类错误，但抓不出类型错误（那要等真实入参）；</li>
 *   <li>抽取规则里的 JSONPath 逐个试解析——写错一个路径，整个接口的返回值就是空的。</li>
 * </ol>
 *
 * @author carbon
 */
@Service
public class OperationServiceImpl implements IOperationService {

    private static final Logger log = LoggerFactory.getLogger(OperationServiceImpl.class);

    /** 占位符替换用；只做结构校验，因此换成数字即可，不区分字符串内外 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[^}]*}}");

    private final ConnectorOperationMapper operationMapper;
    private final IConnectorService connectorService;
    private final PartnerCredentialMapper credentialMapper;
    private final TemplateRenderer templateRenderer;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public OperationServiceImpl(ConnectorOperationMapper operationMapper,
                                IConnectorService connectorService,
                                PartnerCredentialMapper credentialMapper,
                                TemplateRenderer templateRenderer,
                                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.operationMapper = operationMapper;
        this.connectorService = connectorService;
        this.credentialMapper = credentialMapper;
        this.templateRenderer = templateRenderer;
        this.objectMapper = objectMapper;
    }

    @Override
    public IPage<OperationVo> page(Long connectorId, PageQuery pageQuery, String keyword, Integer status) {
        connectorService.requireById(connectorId);

        LambdaQueryWrapper<ConnectorOperation> wrapper = new LambdaQueryWrapper<ConnectorOperation>()
                .eq(ConnectorOperation::getConnectorId, connectorId)
                .eq(status != null, ConnectorOperation::getStatus, status)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(ConnectorOperation::getName, keyword)
                        .or().like(ConnectorOperation::getCode, keyword)
                        .or().like(ConnectorOperation::getPath, keyword))
                .orderByAsc(ConnectorOperation::getCode);

        Page<ConnectorOperation> page = new Page<>(pageQuery.safePageNum(), pageQuery.safePageSize());
        return operationMapper.selectPage(page, wrapper).convert(this::toVo);
    }

    @Override
    public OperationVo detail(Long connectorId, Long id) {
        return toVo(requireOwned(connectorId, id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long connectorId, OperationBo bo) {
        connectorService.requireById(connectorId);
        assertCodeAvailable(connectorId, bo.getCode(), null);
        validate(bo);
        assertCredential(connectorId, bo);

        ConnectorOperation entity = new ConnectorOperation();
        entity.setConnectorId(connectorId);
        applyTo(entity, bo);
        operationMapper.insert(entity);

        log.info("接口创建成功 connectorId={} code={} id={}", connectorId, bo.getCode(), entity.getId());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long connectorId, Long id, OperationBo bo) {
        ConnectorOperation entity = requireOwned(connectorId, id);
        if (!entity.getCode().equals(bo.getCode())) {
            assertCodeAvailable(connectorId, bo.getCode(), id);
        }
        validate(bo);
        assertCredential(connectorId, bo);

        applyTo(entity, bo);
        operationMapper.updateById(entity);
        log.info("接口更新成功 id={} code={}", id, bo.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long connectorId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 带 connectorId 条件删除：防止前端传别人的接口 id 把它们删掉
        operationMapper.delete(new LambdaQueryWrapper<ConnectorOperation>()
                .eq(ConnectorOperation::getConnectorId, connectorId)
                .in(ConnectorOperation::getId, ids));
        log.info("接口删除成功 connectorId={} ids={}", connectorId, ids);
    }

    @Override
    public List<ConnectorOperation> listEnabled(Long connectorId) {
        return operationMapper.selectList(new LambdaQueryWrapper<ConnectorOperation>()
                .eq(ConnectorOperation::getConnectorId, connectorId)
                .eq(ConnectorOperation::getStatus, 1)
                .orderByAsc(ConnectorOperation::getCode));
    }

    // ------------------------------------------------------------------ 内部方法

    private ConnectorOperation requireOwned(Long connectorId, Long id) {
        ConnectorOperation entity = operationMapper.selectById(id);
        if (entity == null || !entity.getConnectorId().equals(connectorId)) {
            throw ServiceException.of(BizError.OPERATION_NOT_FOUND,
                    "接口不存在：id=" + id + "（连接器 id=" + connectorId + "）");
        }
        return entity;
    }

    private void assertCodeAvailable(Long connectorId, String code, Long excludeId) {
        LambdaQueryWrapper<ConnectorOperation> wrapper = new LambdaQueryWrapper<ConnectorOperation>()
                .eq(ConnectorOperation::getConnectorId, connectorId)
                .eq(ConnectorOperation::getCode, code);
        if (excludeId != null) {
            wrapper.ne(ConnectorOperation::getId, excludeId);
        }
        if (operationMapper.exists(wrapper)) {
            throw ServiceException.param("同一连接器下编码不能重复：" + code);
        }
    }

    private void validate(OperationBo bo) {
        scanTemplate("path", bo.getPath());
        if (bo.getQueryParams() != null) {
            bo.getQueryParams().forEach((k, v) -> scanTemplate("queryParams." + k, v));
        }
        if (bo.getHeaders() != null) {
            bo.getHeaders().forEach((k, v) -> scanTemplate("headers." + k, v));
        }
        if ("json".equals(bo.getBodyType()) && bo.getBodyTemplate() != null && !bo.getBodyTemplate().isBlank()) {
            scanTemplate("bodyTemplate", bo.getBodyTemplate());
            String probe = PLACEHOLDER.matcher(bo.getBodyTemplate()).replaceAll("0");
            try {
                objectMapper.readTree(probe);
            } catch (Exception e) {
                throw ServiceException.param("bodyTemplate 不是合法 JSON（把占位符替换成 0 后仍无法解析）："
                        + e.getMessage());
            }
        }
        validateExtract(bo.getResponseExtract());
    }

    /**
     * 凭证覆盖的归属校验。
     *
     * <p>只允许挂到**本接口所属接入点的那个合作方**名下的凭证。跨合作方挂凭证是
     * 「复制粘贴配错」的典型形态：KV 表里看着都对，调用时才把 A 家的密钥发给 B 家的地址。
     */
    private void assertCredential(Long connectorId, OperationBo bo) {
        String mode = bo.getCredentialMode() == null ? "INHERIT" : bo.getCredentialMode();
        if (!"OVERRIDE".equals(mode)) {
            return;
        }
        if (bo.getCredentialId() == null) {
            throw ServiceException.param("凭证模式为 OVERRIDE 时必须指定凭证");
        }
        PartnerCredential credential = credentialMapper.selectById(bo.getCredentialId());
        if (credential == null) {
            throw ServiceException.of(BizError.CREDENTIAL_NOT_FOUND, "凭证不存在：id=" + bo.getCredentialId());
        }
        Long partnerId = connectorService.requireById(connectorId).getPartnerId();
        if (partnerId == null || !partnerId.equals(credential.getPartnerId())) {
            throw ServiceException.param("凭证不属于该接入点的合作方：凭证 id=" + bo.getCredentialId());
        }
    }

    private void validateExtract(List<OperationBo.ExtractBo> extract) {
        if (extract == null) {
            return;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (OperationBo.ExtractBo item : extract) {
            if (!names.add(item.getName())) {
                throw ServiceException.param("抽取字段名重复：" + item.getName());
            }
            if (!item.getJsonpath().startsWith("$")) {
                throw ServiceException.param("JSONPath 必须以 $ 开头：" + item.getJsonpath());
            }
            try {
                com.jayway.jsonpath.JsonPath.compile(item.getJsonpath());
            } catch (Exception e) {
                throw ServiceException.param("JSONPath 语法错误：" + item.getJsonpath() + "，" + e.getMessage());
            }
        }
    }

    private void scanTemplate(String where, String template) {
        if (template == null || template.isBlank()) {
            return;
        }
        try {
            templateRenderer.scan(template);
        } catch (RuntimeException e) {
            throw ServiceException.param(where + " 模板语法错误：" + e.getMessage());
        }
    }

    private void applyTo(ConnectorOperation entity, OperationBo bo) {
        entity.setCode(bo.getCode());
        entity.setName(bo.getName());
        entity.setMethod(bo.getMethod().toUpperCase());
        entity.setPath(bo.getPath());
        entity.setQueryParams(bo.getQueryParams() == null ? null : JsonUtils.toJson(bo.getQueryParams()));
        entity.setHeaders(bo.getHeaders() == null ? null : JsonUtils.toJson(bo.getHeaders()));
        entity.setBodyType(bo.getBodyType() == null ? "none" : bo.getBodyType());
        entity.setBodyTemplate(bo.getBodyTemplate());
        entity.setResponseExtract(bo.getResponseExtract() == null ? null : JsonUtils.toJson(bo.getResponseExtract()));
        entity.setPreScript(bo.getPreScript());
        entity.setPostScript(bo.getPostScript());
        entity.setScriptTimeoutMs(bo.getScriptTimeoutMs() == null ? 2_000 : bo.getScriptTimeoutMs());
        entity.setTimeoutMs(bo.getTimeoutMs());
        entity.setRetry(bo.getRetry() == null ? null : JsonUtils.toJson(bo.getRetry()));
        String mode = bo.getCredentialMode() == null ? "INHERIT" : bo.getCredentialMode();
        entity.setCredentialMode(mode);
        // 非 OVERRIDE 时清掉凭证 id：否则把模式改回 INHERIT 后旧凭证还藏在行里，
        // 下次有人把模式改回 OVERRIDE 就会拿到一条早已不同意的凭证。
        entity.setCredentialId("OVERRIDE".equals(mode) ? bo.getCredentialId() : null);
        entity.setStatus(bo.getStatus() == null ? 1 : bo.getStatus());
    }

    private OperationVo toVo(ConnectorOperation entity) {
        OperationVo vo = OperationVo.of(entity,
                entity.getQueryParams() == null ? Map.of() : JsonUtils.parseMap(entity.getQueryParams()),
                entity.getHeaders() == null ? Map.of() : JsonUtils.parseMap(entity.getHeaders()),
                entity.getResponseExtract() == null ? List.of() : JsonUtils.parseList(entity.getResponseExtract()),
                entity.getRetry() == null ? Map.of() : JsonUtils.parseMap(entity.getRetry()));
        // 只回名称不回密文：详情页要显示「用了哪条凭证」，而密钥永远在服务端
        if (entity.getCredentialId() != null) {
            PartnerCredential credential = credentialMapper.selectById(entity.getCredentialId());
            if (credential != null) {
                vo.setCredentialName(credential.getName());
            }
        }
        return vo;
    }
}
