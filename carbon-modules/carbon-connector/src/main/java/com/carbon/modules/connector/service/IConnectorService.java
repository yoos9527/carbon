package com.carbon.modules.connector.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.modules.connector.domain.bo.ConnectorBo;
import com.carbon.modules.connector.domain.bo.ConnectorCopyBo;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.connector.domain.vo.ConnectorVo;

import java.util.List;
import java.util.Map;

/**
 * 接入点管理。
 *
 * <p>方法分成两组，调用方要注意区别：
 * <ul>
 *   <li>{@code page/detail/create/update/delete/updateStatus} —— 管理端用，返回 VO；</li>
 *   <li>{@code require* / listEnabled} —— 运行时（引擎、网关）用，返回实体。
 *       这些方法抛的不是「参数错误」而是 {@code CONNECTOR_NOT_FOUND}，因为调用链上的人已经改不了它。</li>
 * </ul>
 *
 * <p><b>注意</b>：这里没有「取鉴权配置」的方法了。密钥属于合作方，
 * 由 {@code com.carbon.modules.partner.service.ICredentialService#resolve} 沿
 * 「接口 → 接入点 → 合作方默认凭证」解析。接入点自己只持有一个 {@code credentialId} 偏好。
 *
 * <p>类名与 {@code Connector}/{@code connectorId} 这套词沿用历史命名：
 * 它们在编排 DSL、调用日志、网关路径里出现了一百多处，改名只会把风险扩散出去。
 *
 * @author carbon
 */
public interface IConnectorService {

    /** 列表页排序白名单（驼峰属性名） */
    List<String> SORTABLE = List.of("code", "name", "category", "status", "createdAt", "updatedAt");

    IPage<ConnectorVo> page(PageQuery pageQuery, Long partnerId, String category, Integer status, String keyword);

    ConnectorVo detail(Long id);

    /** @return 新接入点 id */
    Long create(ConnectorBo bo);

    void update(Long id, ConnectorBo bo);

    /**
     * 复制接入点（含接口）。
     *
     * <p>副本**一律以停用状态落地**，即使源接入点是启用的：它指向同一个三方地址，
     * 一创建就能被网关调到，等于「复制一下就多了一个生产入口」。
     *
     * @param bo 可空；code/name 留空由服务端推导（`<原编码>-copy`、`<原名> 副本`）
     * @return 新接入点 id
     */
    Long copy(Long id, ConnectorCopyBo bo);

    /** 真删，连带删掉接口（接入点已随 V8 迁移去掉 deleted 列语义） */
    void delete(List<Long> ids);

    void updateStatus(Long id, Integer status);

    /**
     * 按编码取接入点，供试调与网关路径使用。
     *
     * @throws com.carbon.common.core.exception.ServiceException {@code CONNECTOR_NOT_FOUND}
     */
    Connector requireByCode(String code);

    Connector requireById(Long id);

    /**
     * 按编码取接口，并校验它确实属于该接入点（防止拿 A 接入点的 operationCode 打 B 的 baseUrl）。
     *
     * @throws com.carbon.common.core.exception.ServiceException {@code OPERATION_NOT_FOUND}
     */
    ConnectorOperation requireOperation(Long connectorId, String operationCode);

    /** 接入点下的接口数量，列表页展示用 */
    Map<Long, Integer> countOperations(List<Long> connectorIds);

    /**
     * 全部启用的接入点（不分页）。
     *
     * <p>只给网关的 OpenAPI 文档生成用：那是一份「对外能力清单」，
     * 分页反而会把文档切成几段。
     */
    List<Connector> listEnabled();
}
