package com.carbon.modules.connector.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.modules.connector.domain.bo.OperationBo;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.connector.domain.vo.OperationVo;

import java.util.List;

/**
 * 接口（Operation）管理。
 *
 * <p>所有方法都以 {@code connectorId} 为主键的一部分：接口是从属于连接器的，
 * 没有「全局接口」这种游离概念，URL 里也永远带着连接器 id。
 *
 * @author carbon
 */
public interface IOperationService {

    IPage<OperationVo> page(Long connectorId, PageQuery pageQuery, String keyword, Integer status);

    OperationVo detail(Long connectorId, Long id);

    Long create(Long connectorId, OperationBo bo);

    void update(Long connectorId, Long id, OperationBo bo);

    void delete(Long connectorId, List<Long> ids);
}
