package com.carbon.modules.partner.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.modules.partner.domain.bo.PartnerBo;
import com.carbon.modules.partner.domain.entity.Partner;
import com.carbon.modules.partner.domain.vo.PartnerVo;

import java.util.List;
import java.util.Map;

/**
 * 合作方管理。
 *
 * @author carbon
 */
public interface IPartnerService {

    List<String> SORTABLE = List.of("code", "name", "status", "priority", "createdAt", "updatedAt");

    IPage<PartnerVo> page(PageQuery pageQuery, Integer status, String keyword);

    PartnerVo detail(Long id);

    Long create(PartnerBo bo);

    void update(Long id, PartnerBo bo);

    void delete(Long id);

    /**
     * 改状态，返回影响面（旗下接入点/接口/凭证数量）。
     *
     * <p>返回影响面是因为「停用」是唯一会连坐的写操作：
     * 先告诉人有多少接口会立刻不可用，再让他决定要不要点。
     */
    Map<String, Object> updateStatus(Long id, Integer status);

    Partner requireById(Long id);

    Partner requireByCode(String code);

    List<Partner> listEnabled();

    /** id → 名称，列表拼装用 */
    Map<Long, String> nameMap(List<Long> ids);
}
