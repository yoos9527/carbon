package com.carbon.common.log;

import com.carbon.common.json.JsonUtils;
import com.carbon.common.log.domain.SysOperLog;
import com.carbon.common.log.mapper.SysOperLogMapper;
import com.carbon.common.sensitive.MaskingUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link Log} 的切面实现。
 *
 * <p>四个刻意的选择：
 * <ol>
 *   <li><b>写在事务之外</b>（{@code @Order(100)}，比业务事务更外层）：业务回滚时日志照样留下——
 *       「谁试过改、改失败了」正是排查最需要的那条信息，跟着事务一起回滚就白记了。</li>
 *   <li><b>同步写</b>：管理面是低频操作（人点按钮），同步插入省掉了队列与丢日志的运维问题。
 *       数据面（{@code invoke_log}）才需要攒批异步，两者不该套用同一套机制。</li>
 *   <li><b>参数只存脱敏后的 JSON</b>：直接存 body 会把 {@code app_secret}、{@code clientSecret}
 *       原样写进日志表——日志表的读者通常比操作者权限更高。走
 *       {@link MaskingUtils#maskJson(String)} 按 key 打码，并截断 2000 字符。</li>
 *   <li><b>不记 query string</b>：{@code /api/apps/{id}/secret?secret=...} 这类调用
 *       会把密钥漏在 {@code request_uri} 里，所以只存 path。</li>
 * </ol>
 *
 * <p>日志写入失败<b>绝不影响业务</b>：catch 住只打 warn。审计是可观测性，
 * 不该成为主流程的可用性风险。
 *
 * @author carbon
 */
@Aspect
@Component
@Order(100)
public class LogAspect {

    private static final Logger log = LoggerFactory.getLogger(LogAspect.class);

    private static final int PARAMS_MAX = 2000;
    private static final int UA_MAX = 255;
    private static final int ERROR_MAX = 500;

    private final SysOperLogMapper mapper;

    public LogAspect(SysOperLogMapper mapper) {
        this.mapper = mapper;
    }

    @Around("@annotation(annotation)")
    public Object around(ProceedingJoinPoint point, Log annotation) throws Throwable {
        long start = System.nanoTime();
        boolean success = false;
        String errorMsg = null;
        try {
            Object result = point.proceed();
            success = true;
            return result;
        } catch (Throwable t) {
            errorMsg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            throw t;
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            persist(point, annotation, success, errorMsg, costMs);
        }
    }

    private void persist(ProceedingJoinPoint point, Log annotation, boolean success, String errorMsg, long costMs) {
        try {
            MethodSignature signature = (MethodSignature) point.getSignature();
            SysOperLog entity = new SysOperLog();
            entity.setTitle(annotation.title());
            entity.setBusinessType(annotation.businessType().name());
            entity.setMethod(signature.getDeclaringType().getSimpleName() + "." + signature.getName());
            entity.setParams(truncate(MaskingUtils.maskJson(argsJson(point.getArgs()),
                    MaskingUtils.CREDENTIAL_CONTAINER_KEYS), PARAMS_MAX));
            entity.setStatus(success ? 1 : 0);
            entity.setErrorMsg(truncate(errorMsg, ERROR_MAX));
            entity.setDurationMs(costMs);
            entity.setCreatedAt(LocalDateTime.now());

            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                entity.setRequestMethod(request.getMethod());
                entity.setRequestUri(request.getRequestURI());
                entity.setClientIp(clientIp(request));
                entity.setUserAgent(truncate(request.getHeader("User-Agent"), UA_MAX));
            }
            mapper.insert(entity);
        } catch (RuntimeException e) {
            // 审计失败不能反过来打挂业务
            log.warn("[oper-log] 写入失败 title={}：{}", annotation.title(), e.toString());
        }
    }

    /**
     * 参数列表合并成一个 JSON 数组。
     *
     * <p>跳过 Servlet 对象与上传文件：它们序列化出来是几千字符的容器内部结构，
     * 既没信息量又容易把截断预算吃光。
     */
    private String argsJson(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        List<Object> kept = new ArrayList<>(args.length);
        for (Object arg : args) {
            if (arg == null || arg instanceof HttpServletRequest || arg instanceof HttpServletResponse
                    || arg instanceof MultipartFile) {
                continue;
            }
            kept.add(arg);
        }
        try {
            return JsonUtils.toJson(kept);
        } catch (RuntimeException e) {
            return String.valueOf(kept);
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim().toLowerCase(Locale.ROOT);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim().toLowerCase(Locale.ROOT);
        }
        return request.getRemoteAddr();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
