package com.carbon.modules.partner.controller;

import com.carbon.common.core.domain.R;
import com.carbon.common.idempotent.RepeatSubmit;
import com.carbon.common.log.BusinessType;
import com.carbon.common.log.Log;
import com.carbon.common.web.domain.BaseController;
import com.carbon.modules.partner.domain.bo.CredentialBo;
import com.carbon.modules.partner.domain.vo.CredentialVo;
import com.carbon.modules.partner.service.ICredentialService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 合作方凭证管理接口。
 *
 * <p>路径挂在合作方下面而不是平铺成 {@code /api/credentials}：
 * 凭证编码只在合作方内唯一，平铺路径会让「query」这种编码在你的库里出现十几次。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/partners/{partnerId}/credentials")
public class CredentialController extends BaseController {

    private final ICredentialService credentialService;

    public CredentialController(ICredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping
    public R<List<CredentialVo>> list(@PathVariable Long partnerId) {
        return R.ok(credentialService.list(partnerId));
    }

    @GetMapping("/{id}")
    public R<CredentialVo> detail(@PathVariable Long partnerId, @PathVariable Long id) {
        return R.ok(credentialService.detail(partnerId, id));
    }

    @Log(title = "合作方凭证", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Long> create(@PathVariable Long partnerId, @Valid @RequestBody CredentialBo bo) {
        return R.ok(credentialService.create(partnerId, bo));
    }

    @Log(title = "合作方凭证", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long partnerId, @PathVariable Long id,
                          @Valid @RequestBody CredentialBo bo) {
        credentialService.update(partnerId, id, bo);
        return R.ok();
    }

    @Log(title = "合作方凭证", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long partnerId, @PathVariable Long id) {
        credentialService.delete(partnerId, id);
        return R.ok();
    }

    /** 设为默认凭证（同一合作方只会有一个默认） */
    @Log(title = "合作方凭证", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable Long partnerId, @PathVariable Long id) {
        credentialService.setDefault(partnerId, id);
        return R.ok();
    }

    /** 复制凭证：密文原样搬过去，副本默认停用 */
    @Log(title = "合作方凭证", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/{id}/copy")
    public R<Long> copy(@PathVariable Long partnerId, @PathVariable Long id) {
        return R.ok(credentialService.copy(partnerId, id));
    }
}
