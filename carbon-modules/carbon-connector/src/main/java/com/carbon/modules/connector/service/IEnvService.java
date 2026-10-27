package com.carbon.modules.connector.service;

import com.carbon.modules.connector.domain.entity.Env;

import java.util.List;
import java.util.Map;

/**
 * 环境（{@code {{env.xxx}}} 的来源）。
 *
 * <p>连接器只配一份 {@code base_url}，域名差异全部由环境提供：
 * {@code {{env.host}}}、{@code {{env.appId}}} 之类在渲染前被注入到渲染上下文里。
 *
 * @author carbon
 */
public interface IEnvService {

    List<Env> list();

    /**
     * @param envCode {@code null} 或空 → 取默认环境（{@code is_default=1} 或 code=dev）
     * @throws com.carbon.common.core.exception.ServiceException 找不到时 {@code PARAM_INVALID}
     */
    Env requireByCode(String envCode);

    /**
     * 环境变量表，供 {@code {{env.xxx}}} 取值。
     *
     * <p>额外注入两个派生字段：{@code env.code} 与 {@code env.baseUrlPrefix}，
     * 这样模板里既能用自定义变量，也能直接用环境标识。
     */
    Map<String, Object> varsOf(String envCode);
}
