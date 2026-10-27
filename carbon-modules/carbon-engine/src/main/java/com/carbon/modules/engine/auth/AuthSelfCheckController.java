package com.carbon.modules.engine.auth;

import com.carbon.common.core.domain.R;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.framework.http.HttpExecutor;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.domain.entity.ConnectorAuth;
import com.carbon.modules.connector.service.IConnectorService;
import com.carbon.modules.engine.mask.MaskingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连接器鉴权自检：把「配置能不能用」从「调一次业务接口看报什么错」里拆出来。
 *
 * <p>为什么值得单独一个接口：token 类鉴权（{@code oauth2_cc} / {@code login_token}）失败时，
 * 错误会伪装成业务失败——「接口返回 401」「取不到数据」，配置期的人得猜是地址错了、
 * 密钥错了还是 tokenUrl 写错了。自检只做一件事：**真的去换一次 token**，把成功/失败与耗时
 * 直接回给配置者；顺便把 token 写进缓存，紧接着的试调就不用再等一次网络往返。
 *
 * <p>三条约束：
 * <ul>
 *   <li>返回值里**只有脱敏后的键**（{@code Authorization: Bearer ******}），自检不能变成密钥回显口子；</li>
 *   <li>不做任何业务请求——只碰 tokenUrl / loginUrl，避免「点一下自检，给三方打了一笔真单」；</li>
 *   <li>失败**不抛异常**而是 {@code ok=false}：自检的失败是结论，不是异常，前端要拿它渲染原因。</li>
 * </ul>
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/connectors/{id}/auth")
public class AuthSelfCheckController {

    private static final Logger log = LoggerFactory.getLogger(AuthSelfCheckController.class);

    private final IConnectorService connectorService;
    private final AuthResolver authResolver;
    private final HttpExecutor httpExecutor;

    public AuthSelfCheckController(IConnectorService connectorService,
                                   AuthResolver authResolver,
                                   HttpExecutor httpExecutor) {
        this.connectorService = connectorService;
        this.authResolver = authResolver;
        this.httpExecutor = httpExecutor;
    }

    /**
     * 执行一次自检。
     *
     * <p>注意：token 类鉴权**第二次点会命中缓存**（{@code latencyMs} 接近 0、{@code expiresIn} 不变），
     * 那是正常现象而非「没真的请求」——想强制重新换 token，先改一次配置（保存会清缓存）。
     *
     * @param id 连接器 id
     * @return {@code {authType, ok, latencyMs, expiresIn, headers, queryParams, error}}
     */
    @PostMapping("/test")
    public R<SelfCheck> test(@PathVariable Long id) {
        Connector connector = connectorService.requireById(id);
        ConnectorAuth auth = connectorService.findAuth(id);
        String authType = auth == null || auth.getAuthType() == null ? "none" : auth.getAuthType();
        Map<String, Object> plain = connectorService.authConfigPlain(id);

        long startedAt = System.currentTimeMillis();
        try {
            AuthResolver.AuthOutcome outcome = authResolver.resolve(id, authType, plain, httpExecutor);
            return R.ok(new SelfCheck(authType, true, System.currentTimeMillis() - startedAt,
                    outcome.ttlMs() > 0 ? outcome.ttlMs() / 1000 : null,
                    MaskingUtils.maskHeaders(outcome.headers(), outcome.sensitiveKeys()),
                    MaskingUtils.maskHeaders(outcome.queryParams(), outcome.sensitiveKeys()),
                    null));
        } catch (ServiceException e) {
            // AUTH_FAILED 的文案是我们自己拼的（「获取 OAuth2 token 失败：HTTP 401，…」），可以直接给
            return R.ok(fail(authType, startedAt, e.getMessage()));
        } catch (RuntimeException e) {
            // 网络/解析异常的原文里有内网地址，进日志可以，回给前端要收敛
            log.warn("[auth] 自检失败 connectorId={} type={}", id, authType, e);
            return R.ok(fail(authType, startedAt, "鉴权自检失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage())));
        }
    }

    private SelfCheck fail(String authType, long startedAt, String error) {
        return new SelfCheck(authType, false, System.currentTimeMillis() - startedAt,
                null, Map.of(), Map.of(), error);
    }

    /**
     * @param authType    实际生效的鉴权类型（未配置为 {@code none}）
     * @param ok          自检是否通过
     * @param latencyMs   解析耗时；token 类就是「换一次 token」的往返耗时
     * @param expiresIn   token 有效期（秒），静态鉴权为 {@code null}
     * @param headers     将注入的请求头（**已脱敏**）
     * @param queryParams 将注入的查询参数（**已脱敏**）
     * @param error       失败原因；成功为 {@code null}
     */
    public record SelfCheck(String authType,
                            boolean ok,
                            long latencyMs,
                            Long expiresIn,
                            Map<String, String> headers,
                            Map<String, String> queryParams,
                            String error) {

        public SelfCheck {
            headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
            queryParams = queryParams == null ? Map.of() : new LinkedHashMap<>(queryParams);
        }
    }
}
