package com.carbon.modules.engine.credential;

import com.carbon.common.core.domain.R;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.sensitive.MaskingUtils;
import com.carbon.framework.http.HttpExecutor;
import com.carbon.modules.partner.domain.entity.PartnerCredential;
import com.carbon.modules.partner.service.ICredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 凭证自检：把「这套密钥能不能用」从「调一次业务接口看报什么错」里拆出来。
 *
 * <p>为什么值得单独一个接口：{@code script} 型凭证失败时（换 token 拿到 401、脚本报错），
 * 错误会伪装成业务失败——「接口返回 401」「取不到数据」，配置期的人得猜是地址错了、
 * 密钥错了还是脚本写错了。自检只做一件事：**真的把凭证解析一次**，
 * 把成功/失败与耗时直接回给配置者；顺便把 token 写进缓存，紧接着的试调就不用再等一次网络往返。
 *
 * <p>三条约束：
 * <ul>
 *   <li>返回值里**只有脱敏后的键**（{@code Authorization: Bearer ******}），自检不能变成密钥回显口子；</li>
 *   <li>不做任何业务请求——只执行凭证解析（{@code script} 型只碰脚本里声明要碰的地址），
 *       避免「点一下自检，给三方打了一笔真单」；</li>
 *   <li>失败**不抛异常**而是 {@code ok=false}：自检的失败是结论，不是异常，前端要拿它渲染原因。</li>
 * </ul>
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/partners/{partnerId}/credentials/{id}/test")
public class CredentialSelfCheckController {

    private static final Logger log = LoggerFactory.getLogger(CredentialSelfCheckController.class);

    private final ICredentialService credentialService;
    private final CredentialResolver credentialResolver;
    private final HttpExecutor httpExecutor;

    public CredentialSelfCheckController(ICredentialService credentialService,
                                         CredentialResolver credentialResolver,
                                         HttpExecutor httpExecutor) {
        this.credentialService = credentialService;
        this.credentialResolver = credentialResolver;
        this.httpExecutor = httpExecutor;
    }

    /**
     * 执行一次自检。
     *
     * <p>注意：token 类凭证**第二次点会命中缓存**（{@code latencyMs} 接近 0、{@code expiresIn} 不变），
     * 那是正常现象而非「没真的请求」——想强制重新换 token，先改一次配置（保存会清缓存）。
     */
    @PostMapping
    public R<SelfCheck> test(@PathVariable Long partnerId, @PathVariable Long id) {
        PartnerCredential credential = credentialService.requireOwned(partnerId, id);
        String type = credential.getType() == null ? "none" : credential.getType();
        Map<String, Object> plain = credentialService.plainConfig(credential);

        long startedAt = System.currentTimeMillis();
        try {
            CredentialResolver.CredentialOutcome outcome =
                    credentialResolver.resolve(id, type, plain, httpExecutor);
            return R.ok(new SelfCheck(type, true, System.currentTimeMillis() - startedAt,
                    outcome.ttlMs() > 0 ? outcome.ttlMs() / 1000 : null,
                    MaskingUtils.maskHeaders(outcome.headers(), outcome.sensitiveKeys()),
                    MaskingUtils.maskHeaders(outcome.queryParams(), outcome.sensitiveKeys()),
                    null));
        } catch (ServiceException e) {
            // AUTH_FAILED 的文案是我们自己拼的（「获取 OAuth2 token 失败：HTTP 401，…」），可以直接给
            return R.ok(fail(type, startedAt, e.getMessage()));
        } catch (RuntimeException e) {
            // 网络/解析异常的原文里有内网地址，进日志可以，回给前端要收敛
            log.warn("[credential] 自检失败 credentialId={} type={}", id, type, e);
            return R.ok(fail(type, startedAt, "凭证自检失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage())));
        }
    }

    private SelfCheck fail(String type, long startedAt, String error) {
        return new SelfCheck(type, false, System.currentTimeMillis() - startedAt,
                null, Map.of(), Map.of(), error);
    }

    /**
     * @param type        实际生效的凭证类型（未配置为 {@code none}）
     * @param ok          自检是否通过
     * @param latencyMs   解析耗时；token 类就是「换一次 token」的往返耗时
     * @param expiresIn   token 有效期（秒），静态凭证为 {@code null}
     * @param headers     将注入的请求头（**已脱敏**）
     * @param queryParams 将注入的查询参数（**已脱敏**）
     * @param error       失败原因；成功为 {@code null}
     */
    public record SelfCheck(String type,
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
