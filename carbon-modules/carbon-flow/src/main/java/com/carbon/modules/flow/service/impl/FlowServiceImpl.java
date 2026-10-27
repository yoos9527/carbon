package com.carbon.modules.flow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.framework.dag.compiler.CompiledFlow;
import com.carbon.framework.dag.compiler.ElCompiler;
import com.carbon.framework.dag.dsl.DagDsl;
import com.carbon.framework.dag.registry.ChainRegistry;
import com.carbon.framework.dag.validator.ValidationError;
import com.carbon.modules.flow.domain.bo.FlowBo;
import com.carbon.modules.flow.domain.entity.Flow;
import com.carbon.modules.flow.domain.entity.FlowVersion;
import com.carbon.modules.flow.executor.FlowRunner;
import com.carbon.modules.flow.mapper.FlowMapper;
import com.carbon.modules.flow.mapper.FlowVersionMapper;
import com.carbon.modules.flow.service.IFlowService;
import com.carbon.modules.flow.support.FlowDsls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 流程定义与发布。
 *
 * @author carbon
 */
@Service
public class FlowServiceImpl implements IFlowService {

    private static final Logger log = LoggerFactory.getLogger(FlowServiceImpl.class);

    /** 空画布：新建时给一个能直接跑的最小结构，比给 `{}` 友好。 */
    private static final String BLANK_DSL = """
            {"version":1,"nodes":[
              {"type":"START","id":"start"},
              {"type":"END","id":"end"}
            ],"edges":[{"from":"start","to":"end"}]}""";

    private final FlowMapper flowMapper;
    private final FlowVersionMapper versionMapper;

    public FlowServiceImpl(FlowMapper flowMapper, FlowVersionMapper versionMapper) {
        this.flowMapper = flowMapper;
        this.versionMapper = versionMapper;
    }

    @Override
    public IPage<Flow> page(PageQuery pageQuery, String keyword, Integer status) {
        QueryWrapper<Flow> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like("code", keyword).or().like("name", keyword));
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("updated_at");
        return flowMapper.selectPage(new Page<>(pageQuery.safePageNum(), pageQuery.safePageSize()), wrapper);
    }

    @Override
    public Flow detail(Long id) {
        Flow flow = flowMapper.selectById(id);
        if (flow == null) {
            throw new ServiceException("流程不存在：" + id);
        }
        return flow;
    }

    @Override
    @Transactional
    public Flow create(FlowBo bo) {
        Long exists = flowMapper.selectCount(new QueryWrapper<Flow>().eq("code", bo.getCode()));
        if (exists != null && exists > 0) {
            throw new ServiceException("流程 code 已存在：" + bo.getCode());
        }
        Flow flow = new Flow();
        flow.setCode(bo.getCode());
        flow.setName(bo.getName());
        flow.setDraftDsl(bo.getDraftDsl() == null || bo.getDraftDsl().isBlank() ? BLANK_DSL : bo.getDraftDsl());
        flow.setStatus(bo.getStatus() == null ? 1 : bo.getStatus());
        flowMapper.insert(flow);
        return flow;
    }

    @Override
    @Transactional
    public Flow update(Long id, FlowBo bo) {
        Flow flow = detail(id);
        // code 不可改：chainId 里含 code，改了等于让历史版本全部失联
        if (!flow.getCode().equals(bo.getCode())) {
            throw new ServiceException("流程 code 不可修改（它参与链 id 的生成）");
        }
        flow.setName(bo.getName());
        if (bo.getDraftDsl() != null && !bo.getDraftDsl().isBlank()) {
            flow.setDraftDsl(bo.getDraftDsl());
        }
        if (bo.getStatus() != null) {
            flow.setStatus(bo.getStatus());
        }
        flowMapper.updateById(flow);
        return flow;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Flow flow = detail(id);
        if (flow.getPublishedVersion() != null) {
            // 已发布的流程删掉会让正在跑的 cron/job 失联；要求先停用，是刻意的摩擦
            throw new ServiceException("流程已发布，请先停用（status=0）再删除");
        }
        // 只有未发布的流程才允许走到这里，所以没有链在跑，直接逻辑删除
        flowMapper.deleteById(id);
    }

    @Override
    public List<ValidationError> validate(String dsl) {
        return FlowDsls.validate(FlowDsls.parse(dsl));
    }

    @Override
    @Transactional
    public FlowVersion publish(Long id, String operator) {
        Flow flow = detail(id);
        DagDsl dsl = FlowDsls.parse(flow.getDraftDsl());
        List<ValidationError> errors = FlowDsls.validate(dsl);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .limit(5)
                    .map(e -> e.code() + ": " + e.message())
                    .collect(Collectors.joining("；"));
            throw new ServiceException("流程校验未通过（共 " + errors.size() + " 处）：" + detail);
        }

        int next = flow.getPublishedVersion() == null ? 1 : flow.getPublishedVersion() + 1;
        String chainId = FlowRunner.chainIdOf(flow.getCode(), next);
        // 先编译再落库：编译失败不能留下一个指向「不存在的版本」的指针
        CompiledFlow compiled = ElCompiler.compile(dsl, chainId);
        ChainRegistry.register(compiled, chainId);

        FlowVersion version = new FlowVersion();
        version.setFlowId(id);
        version.setVersion(next);
        version.setDsl(flow.getDraftDsl());
        version.setPublishedBy(operator);
        version.setPublishedAt(LocalDateTime.now());
        versionMapper.insert(version);

        flow.setPublishedVersion(next);
        flow.setStatus(1);
        flowMapper.updateById(flow);
        log.info("[flow] 发布 {} v{}，chain={}，节点 {} 个", flow.getCode(), next, chainId, dsl.nodes().size());
        return version;
    }

    @Override
    public List<FlowVersion> versions(Long id) {
        detail(id);
        return versionMapper.selectList(new QueryWrapper<FlowVersion>()
                .eq("flow_id", id)
                .orderByDesc("version"));
    }
}
