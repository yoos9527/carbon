package com.carbon.common.encrypt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段加解密：AES-256-GCM，密文格式 {@code ENC:v1:<base64(iv||cipherText||tag)>}。
 *
 * <p><b>威胁模型</b>（决定了这里的取舍）：防的是「库被拖走/备份泄露/开发直连生产库看数据」，
 * 不防「能读进程内存的攻击者」——那种情况下密钥也在内存里，任何应用层加密都无解。
 *
 * <p><b>KEK</b>：来自环境变量 {@code CARBON_MASTER_KEY}（32 字节的 base64）。
 * 密钥不进配置文件、不进仓库、不进镜像层；启动时校验不过就直接失败，
 * 绝不「先用空密钥跑起来」（那会把明文密钥写进库，后面再想加密就要全表洗数据）。
 *
 * <p><b>为什么是 GCM 而不是 CBC</b>：GCM 自带完整性校验，密文被改一个字节会直接解不开，
 * 而不是解出一段看似正常的垃圾去调三方接口。
 * <b>为什么每次加密都换 IV</b>：GCM 的 IV 复用会直接泄露明文异或关系。
 *
 * @author carbon
 */
@Component
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    /** 密文前缀：库里能一眼看出哪些列是加密的，也留了版本位便于日后换算法 */
    public static final String PREFIX = "ENC:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${carbon.crypto.master-key:}") String masterKeyBase64) {
        this.key = decodeKey(masterKeyBase64);
    }

    private static SecretKeySpec decodeKey(String masterKeyBase64) {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 CARBON_MASTER_KEY（32 字节的 base64）。"
                            + "生成方式：openssl rand -base64 32。"
                            + "不允许用空密钥启动——否则凭证会以明文写进数据库。");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(masterKeyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CARBON_MASTER_KEY 不是合法的 base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "CARBON_MASTER_KEY 解码后必须是 32 字节（AES-256），当前 " + raw.length + " 字节");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * 加密；入参为空或已加密则原样返回（幂等，允许「重复保存」不改密文）。
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // 不把明文写进异常信息
            throw new IllegalStateException("加密失败: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 解密；非密文（历史明文数据）原样返回，便于灰度迁移。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 换过 KEK 或密文被篡改都会走到这里。日志只说结论，不吐密文
            log.error("解密失败，可能是 CARBON_MASTER_KEY 与密文不匹配或被篡改");
            throw new IllegalStateException("解密失败：主密钥不匹配或密文损坏", e);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
