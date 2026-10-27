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
}
