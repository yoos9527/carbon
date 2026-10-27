package com.carbon.modules.connector.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.modules.connector.domain.bo.ConnectorBo;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.domain.entity.ConnectorAuth;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.connector.domain.vo.ConnectorVo;

import java.util.List;
import java.util.Map;

/**
 * 连接器管理。
 *
 * <p>接口方法分成两组，调用方要注意区别：
 * <ul>
 *   <li>{@code page/detail/create/update/delete/updateStatus} —— 给管理端用，返回脱敏后的 VO；</li>
 *   <li>{@code require* / authConfigPlain} —— 给运行时（引擎、网关）用，返回实体与**明文**配置。
 *       这些方法抛的不是「参数错误」而是 {@code CONNECTOR_NOT_FOUND}，因为调用链上的人已经改不了它。</li>
 * </ul>
 *
 * @author carbon
 */
public interface IConnectorService {

    /** 列表页排序白名单（驼峰属性名） */
    List<String> SORTABLE = List.of("code", "name", "category", "status", "createdAt", "updatedAt");

    IPage<ConnectorVo> page(PageQuery pageQuery, String category, Integer status, String keyword);

    ConnectorVo detail(Long id);

    /** @return 新连接器 id */
    Long create(ConnectorBo bo);

    void update(Long id, ConnectorBo bo);

    /** 真删（表上无 deleted 列），连带删掉鉴权与接口 */
    void delete(List<Long> ids);

    void updateStatus(Long id, Integer status);

    /**
     * 按编码取连接器，供试调与网关路径使用。
     *
     * @throws com.carbon.common.core.exception.ServiceException {@code CONNECTOR_NOT_FOUND}
     */
    Connector requireByCode(String code);

    Connector requireById(Long id);

    /** 未配置鉴权时返回 {@code null}，调用方按 none 处理 */
    ConnectorAuth findAuth(Long connectorId);

    /** 明文配置；无鉴权记录时返回空 Map */
    Map<String, Object> authConfigPlain(Long connectorId);

    /**
     * 按编码取接口，并校验它确实属于该连接器（防止拿 A 连接器的 operationCode 打 B 的 baseUrl）。
     *
     * @throws com.carbon.common.core.exception.ServiceException {@code OPERATION_NOT_FOUND}
     */
    ConnectorOperation requireOperation(Long connectorId, String operationCode);

    /** 连接器下的接口数量，列表页展示用 */
    Map<Long, Integer> countOperations(List<Long> connectorIds);
}
