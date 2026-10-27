package com.carbon.modules.connector.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.R;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.web.domain.BaseController;
import com.carbon.modules.connector.domain.bo.OperationBo;
import com.carbon.modules.connector.domain.vo.OperationVo;
import com.carbon.modules.connector.service.IOperationService;
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
import java.util.Set;
import com.carbon.common.log.BusinessType;
import com.carbon.common.log.Log;
import com.carbon.common.idempotent.RepeatSubmit;

/**
 * 接口管理。挂在连接器下面：{@code /api/connectors/{connectorId}/operations}。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/connectors/{connectorId}/operations")
public class OperationController extends BaseController {

    private static final Set<String> SORTABLE = Set.of("code", "name", "method", "status", "createdAt", "updatedAt");

    private final IOperationService operationService;

    public OperationController(IOperationService operationService) {
        this.operationService = operationService;
    }

    @GetMapping
    public R<TableDataInfo<OperationVo>> page(@PathVariable Long connectorId,
                                              PageQuery pageQuery,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer status) {
        toPage(pageQuery, SORTABLE);
        IPage<OperationVo> page = operationService.page(connectorId, pageQuery, keyword, status);
        return R.ok(toTable(page));
    }

    @GetMapping("/{id}")
    public R<OperationVo> detail(@PathVariable Long connectorId, @PathVariable Long id) {
        return R.ok(operationService.detail(connectorId, id));
    }

    @RepeatSubmit
    @Log(title = "接口", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@PathVariable Long connectorId, @Valid @RequestBody OperationBo bo) {
        return R.ok(operationService.create(connectorId, bo));
    }

    @Log(title = "接口", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long connectorId, @PathVariable Long id,
                          @Valid @RequestBody OperationBo bo) {
        operationService.update(connectorId, id, bo);
        return R.ok();
    }

    @Log(title = "接口", businessType = BusinessType.DELETE)
    @DeleteMapping
    public R<Void> delete(@PathVariable Long connectorId, @RequestBody List<Long> ids) {
        operationService.delete(connectorId, ids);
        return R.ok();
    }
}
