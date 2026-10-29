package com.carbon.modules.partner.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.event.PartnerCredentialChangedEvent;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.connector.mapper.ConnectorMapper;
import com.carbon.modules.connector.mapper.ConnectorOperationMapper;
import com.carbon.modules.partner.domain.bo.PartnerBo;
import com.carbon.modules.partner.domain.entity.Partner;
import com.carbon.modules.partner.domain.entity.PartnerCredential;
import com.carbon.modules.partner.domain.vo.PartnerVo;
import com.carbon.modules.partner.mapper.PartnerCredentialMapper;
import com.carbon.modules.partner.mapper.PartnerMapper;
import com.carbon.modules.partner.service.IPartnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 合作方管理实现。
 *
 * <p>三个刻意的选择：
 * <ul>
 *   <li><b>停用连坐，但先算影响面</b>：合作方停用等于旗下所有接入点立刻不可调用，
 *       所以 {@link #updateStatus} 返回被牵连的数量，页面据此二次确认。</li>
 *   <li><b>有接入点就不许删</b>：删合作方会把「谁在给这家公司付费」的线索一起删掉。
 *       想让它不可用就停用——这正是 status 存在的意义。</li>
 *   <li><b>停用时清 token 缓存</b>：合作方停用后不该还拿着旧 token 打三方，
 *       由凭证变更事件驱动 engine 侧清缓存。</li>
 * </ul>
 *
 * @author carbon
 */
@Service
public class PartnerServiceImpl implements IPartnerService {

    private static final Logger log = LoggerFactory.getLogger(PartnerServiceImpl.class);

    private final PartnerMapper partnerMapper;
    private final PartnerCredentialMapper credentialMapper;
    private final ConnectorMapper endpointMapper;
    private final ConnectorOperationMapper operationMapper;
    private final ApplicationEventPublisher events;

    public PartnerServiceImpl(PartnerMapper partnerMapper,
                              PartnerCredentialMapper credentialMapper,
                              ConnectorMapper endpointMapper,
                              ConnectorOperationMapper operationMapper,
                              ApplicationEventPublisher events) {
        this.partnerMapper = partnerMapper;
        this.credentialMapper = credentialMapper;
        this.endpointMapper = endpointMapper;
        this.operationMapper = operationMapper;
        this.events = events;
    }

    @Override
    public IPage<PartnerVo> page(PageQuery pageQuery, Integer status, String keyword) {
        LambdaQueryWrapper<Partner> wrapper = new LambdaQueryWrapper<Partner>()
                .eq(status != null, Partner::getStatus, status)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Partner::getName, keyword)
                        .or().like(Partner::getCode, keyword)
                        .or().like(Partner::getContractNo, keyword))
                .orderByAsc(Partner::getPriority)
                .orderByDesc(Partner::getUpdatedAt);

        Page<Partner> page = new Page<>(pageQuery.safePageNum(), pageQuery.safePageSize());
        IPage<Partner> result = partnerMapper.selectPage(page, wrapper);

        List<Long> ids = result.getRecords().stream().map(Partner::getId).toList();
        Map<Long, Integer> credentialCounts = countCredentials(ids);
        Map<Long, List<Connector>> endpointsByPartner = endpointsOf(ids);
        Map<Long, Integer> operationCounts = countOperations(endpointsByPartner);
        Map<Long, String> defaultCredentialNames = defaultCredentialNames(ids);

        return result.convert(entity -> {
            PartnerVo vo = PartnerVo.of(entity);
            vo.setCredentialCount(credentialCounts.getOrDefault(entity.getId(), 0));
            List<Connector> endpoints = endpointsByPartner.getOrDefault(entity.getId(), List.of());
            vo.setEndpointCount(endpoints.size());
            vo.setOperationCount(operationCounts.getOrDefault(entity.getId(), 0));
            vo.setDefaultCredentialName(defaultCredentialNames.get(entity.getId()));
            return vo;
        });
    }

    @Override
    public PartnerVo detail(Long id) {
        Partner entity = requireById(id);
        PartnerVo vo = PartnerVo.of(entity);
        List<Connector> endpoints = endpointsOf(List.of(id)).getOrDefault(id, List.of());
        vo.setEndpointCount(endpoints.size());
        vo.setOperationCount(countOperations(Map.of(id, endpoints)).getOrDefault(id, 0));
        vo.setCredentialCount(countCredentials(List.of(id)).getOrDefault(id, 0));
        vo.setDefaultCredentialName(defaultCredentialNames(List.of(id)).get(id));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(PartnerBo bo) {
        assertCodeAvailable(bo.getCode(), null);

        Partner entity = new Partner();
        applyTo(entity, bo);
        partnerMapper.insert(entity);
        log.info("合作方创建成功 id={} code={}", entity.getId(), entity.getCode());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, PartnerBo bo) {
        Partner entity = requireById(id);
        if (!entity.getCode().equals(bo.getCode())) {
            assertCodeAvailable(bo.getCode(), id);
        }
        applyTo(entity, bo);
        partnerMapper.updateById(entity);
        log.info("合作方更新成功 id={} code={}", id, entity.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Partner entity = requireById(id);
        long endpoints = endpointMapper.selectCount(
                new LambdaQueryWrapper<Connector>().eq(Connector::getPartnerId, id));
        if (endpoints > 0) {
            throw ServiceException.param("合作方下还有 " + endpoints + " 个接入点，请先删除接入点，或改为停用");
        }
        // 凭证没有独立的业务价值，跟着合作方一起删
        credentialMapper.delete(new LambdaQueryWrapper<PartnerCredential>()
                .eq(PartnerCredential::getPartnerId, id));
        partnerMapper.deleteById(entity.getId());
        log.info("合作方删除成功 id={} code={}", id, entity.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateStatus(Long id, Integer status) {
        Partner entity = requireById(id);
        entity.setStatus(status);
        partnerMapper.updateById(entity);

        Map<String, Object> impact = impactOf(id);
        // 停用/禁用：让 engine 立刻丢掉这家公司的 token 缓存，否则最长还能用 1 小时
        if (status != null && status != 1) {
            credentialMapper.selectList(new LambdaQueryWrapper<PartnerCredential>()
                            .eq(PartnerCredential::getPartnerId, id))
                    .forEach(c -> events.publishEvent(PartnerCredentialChangedEvent.of(c.getId())));
        }
        log.info("合作方状态变更 id={} status={} 影响面={}", id, status, impact);
        return impact;
    }

    @Override
    public Partner requireById(Long id) {
        Partner entity = id == null ? null : partnerMapper.selectById(id);
        if (entity == null) {
            throw ServiceException.of(BizError.PARTNER_NOT_FOUND, "合作方不存在：id=" + id);
        }
        return entity;
    }

    @Override
    public Partner requireByCode(String code) {
        Partner entity = partnerMapper.selectOne(
                new LambdaQueryWrapper<Partner>().eq(Partner::getCode, code));
        if (entity == null) {
            throw ServiceException.of(BizError.PARTNER_NOT_FOUND, "合作方不存在：" + code);
        }
        return entity;
    }

    @Override
    public List<Partner> listEnabled() {
        return partnerMapper.selectList(new LambdaQueryWrapper<Partner>()
                .eq(Partner::getStatus, 1)
                .orderByAsc(Partner::getPriority)
                .orderByAsc(Partner::getCode));
    }

    @Override
    public Map<Long, String> nameMap(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        partnerMapper.selectList(new LambdaQueryWrapper<Partner>().in(Partner::getId, ids))
                .forEach(p -> names.put(p.getId(), p.getName()));
        return names;
    }

    // ------------------------------------------------------------------ 内部方法

    /** 影响面：旗下接入点 / 接口 / 凭证数量 */
    private Map<String, Object> impactOf(Long partnerId) {
        Map<String, Object> impact = new LinkedHashMap<>();
        List<Connector> endpoints = endpointsOf(List.of(partnerId)).getOrDefault(partnerId, List.of());
        impact.put("endpointCount", endpoints.size());
        impact.put("operationCount", countOperations(Map.of(partnerId, endpoints)).getOrDefault(partnerId, 0));
        impact.put("credentialCount", countCredentials(List.of(partnerId)).getOrDefault(partnerId, 0));
        impact.put("endpointCodes", endpoints.stream().map(Connector::getCode).toList());
        return impact;
    }

    private Map<Long, List<Connector>> endpointsOf(List<Long> partnerIds) {
        if (partnerIds == null || partnerIds.isEmpty()) {
            return Map.of();
        }
        return endpointMapper.selectList(new LambdaQueryWrapper<Connector>()
                        .in(Connector::getPartnerId, partnerIds))
                .stream()
                .collect(Collectors.groupingBy(Connector::getPartnerId));
    }

    private Map<Long, Integer> countOperations(Map<Long, List<Connector>> endpointsByPartner) {
        List<Long> endpointIds = endpointsByPartner.values().stream()
                .flatMap(List::stream).map(Connector::getId).toList();
        if (endpointIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> perEndpoint = new HashMap<>();
        operationMapper.selectList(new LambdaQueryWrapper<ConnectorOperation>()
                        .select(ConnectorOperation::getConnectorId)
                        .in(ConnectorOperation::getConnectorId, endpointIds))
                .forEach(op -> perEndpoint.merge(op.getConnectorId(), 1, Integer::sum));

        Map<Long, Integer> perPartner = new HashMap<>();
        endpointsByPartner.forEach((partnerId, endpoints) -> {
            int sum = endpoints.stream()
                    .mapToInt(e -> perEndpoint.getOrDefault(e.getId(), 0))
                    .sum();
            perPartner.put(partnerId, sum);
        });
        return perPartner;
    }

    private Map<Long, Integer> countCredentials(List<Long> partnerIds) {
        if (partnerIds == null || partnerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        credentialMapper.selectList(new LambdaQueryWrapper<PartnerCredential>()
                        .select(PartnerCredential::getPartnerId)
                        .in(PartnerCredential::getPartnerId, partnerIds))
                .forEach(c -> counts.merge(c.getPartnerId(), 1, Integer::sum));
        return counts;
    }

    private Map<Long, String> defaultCredentialNames(List<Long> partnerIds) {
        if (partnerIds == null || partnerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        credentialMapper.selectList(new LambdaQueryWrapper<PartnerCredential>()
                        .eq(PartnerCredential::getIsDefault, 1)
                        .in(PartnerCredential::getPartnerId, partnerIds))
                .forEach(c -> names.putIfAbsent(c.getPartnerId(), c.getName()));
        return names;
    }

    private void assertCodeAvailable(String code, Long excludeId) {
        LambdaQueryWrapper<Partner> wrapper = new LambdaQueryWrapper<Partner>().eq(Partner::getCode, code);
        if (excludeId != null) {
            wrapper.ne(Partner::getId, excludeId);
        }
        if (partnerMapper.exists(wrapper)) {
            throw ServiceException.param("编码已被占用：" + code);
        }
    }

    private void applyTo(Partner entity, PartnerBo bo) {
        entity.setCode(bo.getCode().trim());
        entity.setName(bo.getName().trim());
        entity.setStatus(bo.getStatus() == null ? 0 : bo.getStatus());
        entity.setContactName(bo.getContactName());
        entity.setContactPhone(bo.getContactPhone());
        entity.setContractNo(bo.getContractNo());
        entity.setPriority(bo.getPriority() == null ? 100 : bo.getPriority());
        entity.setRemark(bo.getRemark());
    }
}
