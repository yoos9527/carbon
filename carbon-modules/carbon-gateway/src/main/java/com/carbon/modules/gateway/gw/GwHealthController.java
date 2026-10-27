package com.carbon.modules.gateway.gw;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关健康检查：{@code GET /gw/health}。
 *
 * <p><b>刻意不要求签名</b>：健康检查的使用者是负载均衡、K8s probe、第三方运维看板，
 * 它们手里没有 appSecret（也不该有——那等于把密钥散播到监控系统里）。
 * 代价是这条路开放给匿名访问，所以它**不能**暴露任何有信息量的东西：
 * 不查库、不报版本号、不列连接器，只回答「这个进程还在」。
 *
 * <p>为什么不复用 {@code /actuator/health}：actuator 会带出组件明细（有时还包括异常栈），
 * 而且它的路径与鉴权策略是全局的，不该为了外部探活去放宽整个 actuator。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/gw")
public class GwHealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("time", LocalDateTime.now());
        body.put("traceId", GwGuard.currentTraceId());
        return body;
    }
}
