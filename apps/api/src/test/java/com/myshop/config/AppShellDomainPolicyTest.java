package com.myshop.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppShellDomainPolicyTest {
    @Test void canonicalizesHttpsHostsAndDefaultPort() {
        assertEquals("https://mc.mc520.top/", AppShellDomainPolicy.parseOrigin("HTTPS://MC.MC520.TOP:443/").canonicalOrigin());
        assertEquals("*.xn--bcher-kva.example", AppShellDomainPolicy.normalizePattern("*.XN--BCHER-KVA.Example."));
        assertNull(AppShellDomainPolicy.normalizePattern("*.Bücher.Example."));
        assertNull(AppShellDomainPolicy.parseOrigin("https://faß.de/"));
        assertEquals("https://xn--fa-hia.de/", AppShellDomainPolicy.parseOrigin("https://xn--fa-hia.de/").canonicalOrigin());
    }

    @Test void rejectsUnsafeOriginShapes() {
        for (String value : new String[]{"http://mc.mc520.top", "https://user@mc.mc520.top",
                "https://mc520.top@evil.com", "https://mc.mc520.top:444", "https://127.0.0.1",
                "https://[::1]", "https://localhost", "https://mc.mc520.top/path", "https://mc\\.mc520.top"}) {
            assertNull(AppShellDomainPolicy.parseOrigin(value), value);
        }
    }

    @Test void wildcardMatchesOnlyOneCompleteSubdomainLabel() {
        String rule = AppShellDomainPolicy.normalizePattern("*.mc520.top");
        assertTrue(AppShellDomainPolicy.matches(rule, "mc.mc520.top"));
        assertFalse(AppShellDomainPolicy.matches(rule, "mc520.top"));
        assertFalse(AppShellDomainPolicy.matches(rule, "mc.mc520.top.evil.com"));
        assertFalse(AppShellDomainPolicy.matches(rule, "evilmc520.top"));
        assertFalse(AppShellDomainPolicy.matches(rule, "a.b.mc520.top"));
    }

    @Test void disabledExactRuleWinsAndSpecificWildcardWins() {
        assertEquals("blocked.mc520.top", AppShellDomainPolicy.selectPattern(
                java.util.List.of("*.mc520.top", "blocked.mc520.top"), "blocked.mc520.top"));
        assertEquals("*.branch.mc520.top", AppShellDomainPolicy.selectPattern(
                java.util.List.of("*.mc520.top", "*.branch.mc520.top"), "shop.branch.mc520.top"));
        assertNull(AppShellDomainPolicy.selectPattern(java.util.List.of("*.mc520.top"), "a.b.mc520.top"));
    }
}
