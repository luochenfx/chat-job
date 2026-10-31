package com.chatjob.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据加解密。API Key 与 Cookie 一律以密文落 SQLite。
 *
 * <p>Windows 用 DPAPI（{@code CryptProtectData}），密钥由操作系统按当前用户账户托管，
 * 文件被拷走或上传网盘都解不开。其他平台退化为 AES-GCM + 本地 master key 文件——
 * 保护强度低于 DPAPI（拿到 key 文件即可解），但至少不是明文，且不会因为平台差异直接崩。
 *
 * <p>密文带前缀标记算法，便于日后换算法时兼容旧数据。
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String DPAPI_PREFIX = "dpapi:";
    private static final String AES_PREFIX = "aesgcm:";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey masterKey;

    /** 首次调用时的落盘位置，仅非 Windows 使用 */
    private static final Path KEY_FILE = Path.of("data", ".master-key");

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        if (windows) {
            try {
                byte[] out = com.sun.jna.platform.win32.Crypt32Util
                        .cryptProtectData(plain.getBytes(StandardCharsets.UTF_8));
                return DPAPI_PREFIX + Base64.getEncoder().encodeToString(out);
            } catch (Throwable t) {
                // 不静默降级成明文：宁可让配置保存失败，也不能把 API Key 写进库
                throw new IllegalStateException("DPAPI 加密失败，拒绝以明文落库: " + t.getMessage(), t);
            }
        }
        return AES_PREFIX + encryptAes(plain);
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        if (cipherText.startsWith(DPAPI_PREFIX)) {
            if (!windows) {
                log.warn("密文由 DPAPI 生成，当前平台无法解密（换了操作系统或 Windows 账户）");
                return null;
            }
            try {
                byte[] raw = Base64.getDecoder().decode(cipherText.substring(DPAPI_PREFIX.length()));
                return new String(com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(raw),
                        StandardCharsets.UTF_8);
            } catch (Throwable t) {
                log.warn("DPAPI 解密失败（可能换了 Windows 账户）: {}", t.getMessage());
                return null;
            }
        }
        if (cipherText.startsWith(AES_PREFIX)) {
            try {
                return decryptAes(cipherText.substring(AES_PREFIX.length()));
            } catch (Exception e) {
                log.warn("AES 解密失败: {}", e.getMessage());
                return null;
            }
        }
        // 未带前缀：视为历史明文数据，原样返回（迁移期兼容）
        return cipherText;
    }

    /** 用于接口回显：不返回明文，只说明"是否已配置" */
    public boolean isConfigured(String cipherText) {
        return cipherText != null && !cipherText.isBlank();
    }

    private String encryptAes(String plain) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] joined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, joined, 0, iv.length);
            System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(joined);
        } catch (Exception e) {
            throw new IllegalStateException("AES 加密失败", e);
        }
    }

    private String decryptAes(String base64) throws Exception {
        byte[] joined = Base64.getDecoder().decode(base64);
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(joined, 0, iv, 0, GCM_IV_LENGTH);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = cipher.doFinal(joined, GCM_IV_LENGTH, joined.length - GCM_IV_LENGTH);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private SecretKey key() {
        if (masterKey != null) {
            return masterKey;
        }
        synchronized (this) {
            if (masterKey != null) {
                return masterKey;
            }
            try {
                byte[] keyBytes;
                if (Files.isRegularFile(KEY_FILE)) {
                    keyBytes = Base64.getDecoder().decode(Files.readString(KEY_FILE).trim());
                } else {
                    keyBytes = new byte[32];
                    random.nextBytes(keyBytes);
                    Files.createDirectories(KEY_FILE.getParent());
                    Files.writeString(KEY_FILE, Base64.getEncoder().encodeToString(keyBytes));
                    try {
                        Files.setPosixFilePermissions(KEY_FILE,
                                PosixFilePermissions.fromString("rw-------"));
                    } catch (Exception ignore) {
                        // Windows 上 POSIX 权限不适用，DPAPI 分支本来也用不到这个文件
                    }
                }
                masterKey = new SecretKeySpec(keyBytes, "AES");
                return masterKey;
            } catch (Exception e) {
                throw new IllegalStateException("无法准备 AES master key", e);
            }
        }
    }
}
