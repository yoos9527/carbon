package com.carbon.common.idempotent;

import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.json.JsonUtils;
import com.carbon.common.redis.RedisUtils;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * {@link RepeatSubmit} 的切面实现。
 *
 * <p>执行顺序 {@code @Order(0)}：放在业务事务之前（外层），
 * 这样重复请求<b>连事务都不开</b>，不会被「后一个请求先提交、前一个再覆盖」坑到。
 *
 * <p><b>Redis 挂了就放行</b>：去重是「防手滑」的优化，不是数据一致性保障
 * （真要一致得靠唯一键）。Redis 不可用时记 warn 并继续执行——
 * 让整个平台因为治不了一个双击就不可用，明显不划算。
 *
 * <p><b>失败即释放</b>：方法抛异常时删掉去重键，用户可以立刻重试；
 * 成功则保留到 TTL 过期——成功的那次其实已经生效了，重试本来就是错的。
 *
 * @author carbon
 */
@Aspect
@Component
@Order(0)
public class RepeatSubmitAspect {

    private static final Logger log = LoggerFactory.getLogger(RepeatSubmitAspect.class);

    private static final String KEY_PREFIX = "repeat:";

    private final RedisUtils redisUtils;

    public RepeatSubmitAspect(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    @Around("@annotation(repeatSubmit)")
    public Object around(ProceedingJoinPoint point, RepeatSubmit repeatSubmit) throws Throwable {
        String key = buildKey(point);
        boolean acquired;
        try {
            acquired = redisUtils.setIfAbsent(key, String.valueOf(System.currentTimeMillis()),
                    Duration.ofMillis(Math.max(1, repeatSubmit.interval())));
        } catch (RuntimeException e) {
            log.warn("[repeat] Redis 不可用，本次不做提交去重：{}", e.toString());
            return point.proceed();
        }
        if (!acquired) {
            log.warn("[repeat] 命中重复提交 key={}", key);
            throw ServiceException.of(BizError.RATE_LIMITED, repeatSubmit.message());
        }
        try {
            return point.proceed();
        } catch (Throwable t) {
            // 业务失败：把窗口放掉，否则用户改完参数再点还是「请勿重复提交」
            try {
                redisUtils.delete(key);
            } catch (RuntimeException ignore) {
                log.debug("[repeat] 释放去重键失败 key={}", key);
            }
            throw t;
        }
    }

    private String buildKey(ProceedingJoinPoint point) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        String method = signature.getDeclaringType().getSimpleName() + "." + signature.getName();
        return KEY_PREFIX + method + ":" + clientIp() + ":" + digest(point.getArgs());
    }

    /**
     * 参数摘要。
     *
     * <p>用 SHA-256 而不是原样拼字符串：key 里不该出现密码、密钥，
     * 而 {@code @RepeatSubmit} 正好会用在「新建连接器（含鉴权密钥）」这种参数上。
     */
    private String digest(Object[] args) {
        StringBuilder sb = new StringBuilder();
        if (args != null) {
            for (Object arg : args) {
                if (arg == null || arg instanceof HttpServletRequest || arg instanceof HttpServletResponse
                        || arg instanceof MultipartFile) {
                    continue;
                }
                sb.append(toJson(arg)).append('\u0000');
            }
        }
        return sha256(sb.toString());
    }

    private String toJson(Object arg) {
        try {
            return JsonUtils.toJson(arg);
        } catch (RuntimeException e) {
            // 序列化不了就退化到 toString：摘要不必稳定跨进程，只要同一请求两次一致即可
            return String.valueOf(arg);
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 一定提供 SHA-256，走到这里说明环境已经不可信
            throw new IllegalStateException(e);
        }
    }

    /**
     * 调用方 IP。
     *
     * <p>代理场景取 {@code X-Forwarded-For} 首个地址；取不到就是直连地址。
     * 现在平台没有登录，「同一个人」只能用 IP 近似——等接入登录后这里应换成 userId，
     * 否则同一个办公室所有人在共用一个 NAT 出口时，会互相把对方的正常提交当成重复。
     */
    private String clientIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "no-request";
        }
        HttpServletRequest request = attrs.getRequest();
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
}
