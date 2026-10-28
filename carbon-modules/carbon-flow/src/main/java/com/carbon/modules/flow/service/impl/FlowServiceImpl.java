package com.carbon.modules.flow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.framework.dag.compiler.CompiledFlow;
import com.carbon.framework.dag.compiler.ElCompiler;
import com.carbon.framework.dag.dsl.DagDsl;
import com.carbon.framework.dag.dsl.node.CallNode;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.registry.ChainRegistry;
import com.carbon.framework.dag.validator.ValidationError;
import com.carbon.modules.connector.service.IConnectorService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
            ],"edges":[{"source":"start","target":"end"}]}""";

    private final FlowMapper flowMapper;
    private final FlowVersionMapper versionMapper;
    private final IConnectorService connectorService;

    public FlowServiceImpl(FlowMapper flowMapper, FlowVersionMapper versionMapper,
                          IConnectorService connectorService) {
        this.flowMapper = flowMapper;
        this.versionMapper = versionMapper;
        this.connectorService = connectorService;
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
        // 列表不回传 draftDsl：那是整张图的 JSON（几十 KB 起步），
        // 一页 10 条就多几百 KB 流量，而列表页只需要名字与版本。
        wrapper.select(Flow.class, field -> !"draft_dsl".equals(field.getColumn()));
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

        // 不能拿「当前指针 + 1」当下一版：回滚之后指针会往回走，再发布就会撞上已存在的版本号
        // （uk_flow_version 直接报唯一键冲突），这里取历史最大值 + 1
        int next = nextVersion(id);
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

    @Override
    @Transactional
    public FlowVersion rollback(Long id, Integer version, String operator) {
        Flow flow = detail(id);
        if (version == null) {
            throw new ServiceException("请指定要回滚到的版本号");
        }
        FlowVersion target = versionMapper.selectOne(new QueryWrapper<FlowVersion>()
                .eq("flow_id", id)
                .eq("version", version));
        if (target == null) {
            throw new ServiceException("版本不存在：v" + version);
        }
        // 已经在这一版上就别重复写库、重复编译（画布上误点两下是常事）
        if (version.equals(flow.getPublishedVersion())) {
            return target;
        }
        DagDsl dsl = FlowDsls.parse(target.getDsl());
        List<ValidationError> errors = FlowDsls.validate(dsl);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .limit(5)
                    .map(e -> e.code() + ": " + e.message())
                    .collect(Collectors.joining("；"));
            // 快照当初是校验通过的，现在还不过说明校验规则变严了。此时把指针指过去，
            // 等于让线上跑一份「按现在的规则根本发布不出去」的图
            throw new ServiceException("该版本按当前校验规则不通过（共 " + errors.size() + " 处），不能回滚：" + detail);
        }
        String chainId = FlowRunner.chainIdOf(flow.getCode(), version);
        ChainRegistry.register(ElCompiler.compile(dsl, chainId), chainId);

        Integer from = flow.getPublishedVersion();
        flow.setPublishedVersion(version);
        // 草稿跟着回滚：否则画布显示 v5 的图、实际跑的是 v2，用户永远对不上
        flow.setDraftDsl(target.getDsl());
        flow.setStatus(1);
        flowMapper.updateById(flow);
        log.info("[flow] 回滚 {} v{} → v{}（operator={}）", flow.getCode(), from, version, operator);
        return target;
    }

    @Override
    public Flow requirePublishedByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ServiceException(BizError.FLOW_NOT_FOUND, "流程编码不能为空");
        }
        Flow flow = flowMapper.selectOne(new QueryWrapper<Flow>().eq("code", code));
        // 「不存在」与「未发布」对外必须同一个错：否则网关就成了一个画布探测工具
        if (flow == null || flow.getStatus() == null || flow.getStatus() == 0) {
            throw new ServiceException(BizError.FLOW_NOT_FOUND, "流程不存在或已停用：" + code);
        }
        if (flow.getPublishedVersion() == null) {
            throw new ServiceException(BizError.FLOW_NOT_FOUND, "流程尚未发布：" + code);
        }
        return flow;
    }

    @Override
    public Set<Long> publishedConnectorIds(Long flowId) {
        Integer version = detail(flowId).getPublishedVersion();
        if (version == null) {
            return Set.of();
        }
        FlowVersion snapshot = versionMapper.selectOne(new QueryWrapper<FlowVersion>()
                .eq("flow_id", flowId)
                .eq("version", version));
        if (snapshot == null) {
            // 版本快照丢了比白名单为空更危险（=不限），所以抛错而不是静默放行
            throw new ServiceException("流程版本快照缺失：flowId=" + flowId + " v" + version);
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (NodeDef node : FlowDsls.parse(snapshot.getDsl()).nodes()) {
            if (node instanceof CallNode call) {
                try {
                    ids.add(connectorService.requireByCode(call.connector()).getId());
                } catch (RuntimeException ignored) {
                    // 连接器已被删：不参与白名单，调用时自然会报 CONNECTOR_NOT_FOUND
                }
            }
        }
        return ids;
    }

    @Override
    public List<Flow> listPublished() {
        return flowMapper.selectList(new QueryWrapper<Flow>()
                .eq("status", 1)
                .isNotNull("published_version")
                // 文档里只需要 code/name，整张图 JSON 不该出现在接口清单里
                .select("id", "code", "name", "published_version")
                .orderByAsc("code"));
    }

    private int nextVersion(Long flowId) {
        List<Object> max = versionMapper.selectObjs(new QueryWrapper<FlowVersion>()
                .select("MAX(version)")
                .eq("flow_id", flowId));
        return max.isEmpty() || max.get(0) == null ? 1 : ((Number) max.get(0)).intValue() + 1;
    }
}
