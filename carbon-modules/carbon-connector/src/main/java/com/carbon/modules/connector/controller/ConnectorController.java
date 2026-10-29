package com.carbon.modules.connector.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.R;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.web.domain.BaseController;
import com.carbon.modules.connector.domain.bo.ConnectorBo;
import com.carbon.modules.connector.domain.bo.ConnectorCopyBo;
import com.carbon.modules.connector.domain.vo.ConnectorVo;
import com.carbon.modules.connector.service.IConnectorService;
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
 * 接入点管理接口。
 *
 * <p>路径是 {@code /api/endpoints}：语义上它已经是「合作方下的访问配置」，
 * 但 Java 类名仍叫 {@code Connector}，因为编排 DSL、调用日志、网关路径里到处在用
 * {@code connector}/{@code connectorId} 这两个词。
 *
 * <p>这里既没有 {@code /auth} 也没有 {@code /{id}/auth/test}：
 * 密钥属于合作方，自检在 {@code /api/partners/{partnerId}/credentials/{id}/test}。
 *
 * <p>路径前缀 {@code /api/endpoints}；所有响应体都是 {@code R<T>}，
 * 分页数据包在 {@code R<TableDataInfo<T>>} 里（前端表格组件直接吃 {@code total/rows}）。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/endpoints")
public class ConnectorController extends BaseController {

    private static final Set<String> SORTABLE = Set.copyOf(IConnectorService.SORTABLE);

    private final IConnectorService connectorService;

    public ConnectorController(IConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    @GetMapping
    public R<TableDataInfo<ConnectorVo>> page(PageQuery pageQuery,
                                              @RequestParam(required = false) Long partnerId,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) String keyword) {
        // toPage 只用于校验排序列白名单；真正的分页参数在 service 内部按 safePage* 归一
        toPage(pageQuery, SORTABLE);
        IPage<ConnectorVo> page = connectorService.page(pageQuery, partnerId, category, status, keyword);
        return R.ok(toTable(page));
    }

    @GetMapping("/{id}")
    public R<ConnectorVo> detail(@PathVariable Long id) {
        return R.ok(connectorService.detail(id));
    }

    @Log(title = "接入点", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Long> create(@Valid @RequestBody ConnectorBo bo) {
        return R.ok(connectorService.create(bo));
    }

    /** 复制连接器：body 可空（`{}` / 不传），code 与 name 留空则自动推导 */
    @Log(title = "接入点", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/{id}/copy")
    public R<Long> copy(@PathVariable Long id,
                        @Valid @RequestBody(required = false) ConnectorCopyBo bo) {
        return R.ok(connectorService.copy(id, bo));
    }

    @Log(title = "接入点", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ConnectorBo bo) {
        connectorService.update(id, bo);
        return R.ok();
    }

    @Log(title = "接入点", businessType = BusinessType.DELETE)
    @DeleteMapping
    public R<Void> delete(@RequestBody List<Long> ids) {
        connectorService.delete(ids);
        return R.ok();
    }

    @Log(title = "接入点", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        connectorService.updateStatus(id, status);
        return R.ok();
    }
}
