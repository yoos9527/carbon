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
}
