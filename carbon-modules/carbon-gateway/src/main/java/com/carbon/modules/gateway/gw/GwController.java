package com.carbon.modules.gateway.gw;

import com.carbon.modules.engine.invoke.InvokeRequest;
import com.carbon.modules.engine.invoke.InvokeResult;
import com.carbon.modules.engine.invoke.InvokeService;
import com.carbon.modules.invokelog.api.InvokeLogEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对外网关入口：{@code /gw/{connectorCode}/{operationCode}}。
 *
 * <p>鉴权、防重放、限流、连接器白名单全部在 {@link GwGuard} 里——与编排入口共用一份实现。
 * 这个类只负责「把 HTTP 请求翻译成 {@link InvokeRequest}」，再把结果包成 {@link GwResult}。
 *
 * <p>错误一律返回**同一个** {@code SIGN_INVALID}，不区分「appKey 不存在 / 签名错 / nonce 重放」——
 * 按错误类型给出差异化提示，等于送对方一个探测工具。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/gw")
public class GwController {

    private final InvokeService invokeService;
    private final GwGuard guard;

    public GwController(InvokeService invokeService, GwGuard guard) {
        this.invokeService = invokeService;
        this.guard = guard;
    }

    /**
     * 网关调用。GET 与 POST 共用同一个入口：签名协议里 body 参与摘要，两者没有本质区别。
     */
    @RequestMapping("/{connectorCode}/{operationCode}")
    public ResponseEntity<GwResult<Map<String, Object>>> invoke(
            @PathVariable String connectorCode,
            @PathVariable String operationCode,
            HttpServletRequest request,
            @RequestBody(required = false) String rawBody) {

        String traceId = GwGuard.currentTraceId();
        long startedAt = System.currentTimeMillis();

        GwGuard.Caller caller = guard.verify(request, rawBody, connectorCode);
        // 网关只做变量填充：query 与 body 顶层字段合并成 input，同名时 body 优先
        Map<String, Object> input = GwGuard.collectInput(request, rawBody);

        InvokeResult result = invokeService.invoke(new InvokeRequest(
                connectorCode, operationCode, input,
                caller.envCode(), Map.of(), false,
                InvokeLogEvent.SOURCE_GATEWAY, caller.appId()));
        return ResponseEntity.ok(GwResult.ok(result.extracted(), traceId,
                result.status(), System.currentTimeMillis() - startedAt));
    }
}
