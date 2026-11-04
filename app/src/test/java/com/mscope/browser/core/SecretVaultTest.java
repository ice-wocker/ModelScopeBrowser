package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 记忆入口的脱敏闸门。用户贴一次 token 让你排错，不能因此把它永久写进记忆文件。
 */
public class SecretVaultTest {

    @Test
    public void redactsGithubToken() {
        String out = SecretVault.redact("我的 token 是 gho_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX 别乱用");
        assertFalse(out.contains("gho_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"));
        assertTrue(out, out.contains("<redacted:github>"));
    }

    @Test
    public void redactsOpenAiKey() {
        String out = SecretVault.redact("sk-abcdefghijklmnopqrstuvwxyz0123456789");
        assertTrue(out, out.startsWith("<redacted:openai>"));
    }

    @Test
    public void keepsKeyNameButDropsValue() {
        String out = SecretVault.redact("password=hunter2 以及 api_key: ak_9f8e7d6c5b");
        assertTrue(out, out.contains("password="));
        assertFalse(out, out.contains("hunter2"));
        assertFalse(out, out.contains("ak_9f8e7d6c5b"));
    }

    @Test
    public void redactsUrlCredentials() {
        String out = SecretVault.redact("https://user:pass@example.com/x");
        assertFalse(out, out.contains("user:pass"));
        assertTrue(out, out.contains("example.com"));
    }

    @Test
    public void redactsPrivateKeyBlock() {
        String body = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----";
        assertFalse(SecretVault.redact(body).contains("MIIEow"));
    }

    @Test
    public void normalTextUntouched() {
        String s = "用户偏好简洁回答，常用中文，设备是 arm64。";
        assertEquals(s, SecretVault.redact(s));
        assertFalse(SecretVault.containsSecret(s));
    }
}
