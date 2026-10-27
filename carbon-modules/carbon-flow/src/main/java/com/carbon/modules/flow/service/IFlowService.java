package com.carbon.modules.flow.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.framework.dag.validator.ValidationError;
import com.carbon.modules.flow.domain.bo.FlowBo;
import com.carbon.modules.flow.domain.entity.Flow;
import com.carbon.modules.flow.domain.entity.FlowVersion;

import java.util.List;

/**
 * 流程定义与版本。
 *
 * @author carbon
 */
public interface IFlowService {

    /** 允许排序的列（白名单，防止 {@code ORDER BY} 注入）。 */
    String[] SORTABLE = {"id", "code", "name", "status", "updated_at"};

    IPage<Flow> page(PageQuery pageQuery, String keyword, Integer status);

    Flow detail(Long id);

    Flow create(FlowBo bo);

    Flow update(Long id, FlowBo bo);

    void delete(Long id);

    /**
     * 校验草稿（不落库）。
     *
     * @return 全部错误；空表示可发布
     */
    List<ValidationError> validate(String dsl);

    /**
     * 发布：编译 → 注册链路 → 落版本快照 → 移动版本指针。
     *
     * @param id       流程 id
     * @param operator 操作者（当前无登录，传固定值或调用方自报）
     */
    FlowVersion publish(Long id, String operator);

    List<FlowVersion> versions(Long id);

    /**
     * 回滚到某个历史版本：把发布指针指回去，并把草稿覆盖成该版本的 DSL。
     *
     * <p>为什么不「追加一个新版本」：版本快照是不可变的历史，回滚是「当前生效的是哪一版」，
     * 追加只会让 history 里出现两个 v2 的等价物，反而看不出到底回滚过。
     *
     * @param id       流程 id
     * @param version  目标版本号（必须已存在）
     * @param operator 操作者
     * @return 被指向的版本快照
     */
    FlowVersion rollback(Long id, Integer version, String operator);

    /**
     * 按 code 取一个**已发布且启用**的流程，供网关 {@code /gw/flows/{flowCode}} 使用。
     *
     * <p>为什么不复用 {@link #detail(Long)}：网关的调用方是外部系统，它只知道自己起的 code；
     * 而且「存在但未发布」必须直接拒掉——运行时才报 {@code publishedVersion=null} 的 NullPointer 更糟。
     *
     * @throws com.carbon.common.core.exception.ServiceException {@code FLOW_NOT_FOUND}
     */
    Flow requirePublishedByCode(String code);

    /**
     * 已发布 DSL 里引用到的连接器 id 集合。
     *
     * <p>网关用它把「应用级连接器白名单」延伸到编排：应用 A 有权调 connector1，
     * 就不该能绕道一个引用了 connector9 的流程拿到 connector9 的数据。
     *
     * <p>连接器已被删除的引用会被跳过（真正调用时会报 {@code CONNECTOR_NOT_FOUND}），
     * 而不是让整个白名单校验直接炸掉。
     */
    java.util.Set<Long> publishedConnectorIds(Long flowId);

    /** 已发布且有启用状态的流程清单（不含 DSL），网关 OpenAPI 文档生成用 */
    List<Flow> listPublished();
}
