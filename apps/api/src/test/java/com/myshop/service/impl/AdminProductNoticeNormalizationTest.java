package com.myshop.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdminProductNoticeNormalizationTest {

    @Test
    void versionChangesOnlyWhenNormalizedTextChanges() {
        assertEquals(5, AdminProductServiceImpl.nextAgentNoticeVersion("  建议零售价\r\n", 5, "建议零售价"));
        assertEquals(6, AdminProductServiceImpl.nextAgentNoticeVersion("旧提示", 5, "新提示"));
        assertEquals(6, AdminProductServiceImpl.nextAgentNoticeVersion("旧提示", 5, "  \r\n "));
        assertEquals("第一行\n第二行", AdminProductServiceImpl.normalizeAgentNoticeText(" 第一行\r\n第二行 "));
        assertNull(AdminProductServiceImpl.normalizeAgentNoticeText(" \r\n\t "));
    }
}
