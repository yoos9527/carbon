package com.carbon.modules.engine.credential;

import com.carbon.framework.http.HttpExecutor;
import com.carbon.framework.http.HttpRequestSpec;
import com.carbon.framework.http.HttpResult;
import com.carbon.common.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 凭证脚本能用的 HTTP 出口（只给 {@code script} 型凭证，不是通用脚本能力）。
 *
 * <p><b>为什么必须开这个口子</b>：现实中一大半三方的鉴权是「先换 token 再带上去」
 * （OAuth2 客户端模式、账号密码登录换 token、带签名的时间戳接口）。
 * 如果不给脚本网络能力，这类供应商就只能继续用平台内置的固定几种模式，
 * 而「用代码描述鉴权」这个需求本身就是为了摆脱固定几种模式。
 *
 * <p><b>风险与边界</b>：脚本是**配置者**写的，配置者本来就能填 {@code baseUrl}、
 * 能拿到明文密钥，因此这里没有引入新的信任边界；真正的约束是三条：
 * <ul>
 *   <li>只暴露 {@code get/postForm/postJson} 三个方法，不给 header 之外的自由度；</li>
 *   <li>超时写死（连接 3s / 读 10s），脚本无法把线程挂死——配合沙箱墙钟仍兜一层；</li>
 *   <li>非 2xx <b>不抛异常</b>，回 {@code status} 让脚本自己判断（很多三方用 400 表达业务错误）。</li>
 * </ul>
 *
 * @author carbon
 */
public class CredentialScriptHttp {

    private static final Logger log = LoggerFactory.getLogger(CredentialScriptHttp.class);

    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final HttpExecutor http;

    public CredentialScriptHttp(HttpExecutor http) {
        this.http = http;
    }

    public boolean available() {
        return http != null;
    }

    /**
     * GET。
     *
     * @param url     完整地址
     * @param headers 请求头（可为 null）
     * @return {@code {status, body, json, error}}
     */
    public Map<String, Object> get(String url, Map<String, ?> headers) {
        return call("GET", url, headers, null, null);
    }

    /**
     * POST 表单（{@code application/x-www-form-urlencoded}），换 token 最常见的形式。
     *
     * @param url     完整地址
     * @param headers 请求头（可为 null）
     * @param form    表单字段
     * @return {@code {status, body, json, error}}
     */
    public Map<String, Object> postForm(String url, Map<String, ?> headers, Map<String, ?> form) {
        String body = form == null ? "" : form.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> URLEncoder.encode(String.valueOf(e.getKey()), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return call("POST", url, headers, "application/x-www-form-urlencoded", body);
    }

    /**
     * POST JSON。
     *
     * @param url     完整地址
     * @param headers 请求头（可为 null）
     * @param body    任意可序列化对象
     * @return {@code {status, body, json, error}}
     */
    public Map<String, Object> postJson(String url, Map<String, ?> headers, Object body) {
        return call("POST", url, headers, "application/json",
                body == null ? null : JsonUtils.toJson(body));
    }

    private Map<String, Object> call(String method, String url, Map<String, ?> headers,
                                     String contentType, String body) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (http == null) {
            result.put("status", -1);
            result.put("error", "未装配 HttpExecutor，脚本无法发请求");
            return result;
        }
        if (url == null || url.isBlank()) {
            result.put("status", -1);
            result.put("error", "请求地址为空");
            return result;
        }
        try {
            HttpRequestSpec.Builder builder = HttpRequestSpec.builder(method, url)
                    .timeouts(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            if (headers != null) {
                headers.forEach((k, v) -> {
                    if (k != null && v != null) {
                        builder.header(k, String.valueOf(v));
                    }
                });
            }
            if (body != null && !body.isEmpty()) {
                builder.body(body);
            }
            HttpResult response = http.execute(builder.build());
            result.put("status", response.getStatus());
            result.put("body", response.getBody());
            result.put("json", parseQuietly(response.getBody()));
            return result;
        } catch (RuntimeException e) {
            // 脚本要能对「网络不通」做决策（比如换备用地址），所以失败也是返回值而不是异常
            log.warn("[credential-script] 请求失败 method={} url={} : {}", method, url, e.toString());
            result.put("status", -1);
            result.put("error", e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()));
            return result;
        }
    }

    private static Object parseQuietly(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parseMap(body);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
