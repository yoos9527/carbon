package com.carbon.modules.partner.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.R;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.idempotent.RepeatSubmit;
import com.carbon.common.log.BusinessType;
import com.carbon.common.log.Log;
import com.carbon.common.web.domain.BaseController;
import com.carbon.modules.partner.domain.bo.PartnerBo;
import com.carbon.modules.partner.domain.vo.PartnerVo;
import com.carbon.modules.partner.service.IPartnerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合作方管理接口。
 *
 * <p>路径前缀 {@code /api/partners}。凭证挂在合作方下面（{@code /api/partners/{id}/credentials}），
 * 因为凭证离开合作方没有意义——它表达的是「这家公司有一套 key」。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/partners")
public class PartnerController extends BaseController {

    private static final Set<String> SORTABLE = Set.copyOf(IPartnerService.SORTABLE);

    private final IPartnerService partnerService;

    public PartnerController(IPartnerService partnerService) {
        this.partnerService = partnerService;
    }

    @GetMapping
    public R<TableDataInfo<PartnerVo>> page(PageQuery pageQuery,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String keyword) {
        toPage(pageQuery, SORTABLE);
        IPage<PartnerVo> page = partnerService.page(pageQuery, status, keyword);
        return R.ok(toTable(page));
    }

    @GetMapping("/{id}")
    public R<PartnerVo> detail(@PathVariable Long id) {
        return R.ok(partnerService.detail(id));
    }

    @Log(title = "合作方", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Long> create(@Valid @RequestBody PartnerBo bo) {
        return R.ok(partnerService.create(bo));
    }

    @Log(title = "合作方", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody PartnerBo bo) {
        partnerService.update(id, bo);
        return R.ok();
    }

    @Log(title = "合作方", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        partnerService.delete(id);
        return R.ok();
    }

    /**
     * 改状态。响应体里带影响面（旗下接入点/接口/凭证数量），前端据此做二次确认。
     */
    @Log(title = "合作方", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status")
    public R<Map<String, Object>> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        return R.ok(partnerService.updateStatus(id, status));
    }

    /** 供下拉框使用：只返回正常状态的合作方 */
    @GetMapping("/options")
    public R<List<PartnerVo>> options() {
        return R.ok(partnerService.listEnabled().stream().map(PartnerVo::of).toList());
    }
}
