package com.myshop.supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.Product;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SupplierPresentationTest {
    private final ObjectMapper json=new ObjectMapper();
    private final SupplierPresentation presentation=new SupplierPresentation();

    @Test void convertsHtmlToSafeMarkdownAndResolvesOnlyHttpImages() throws Exception {
        var snapshot=json.readTree("""
                {"imgUrl":"/cover.png","goodsDescribe":"<p>安全简介</p>",
                 "goodsDetail":"<h2>说明</h2><p><strong>安全文字</strong> <a href='/help'>帮助</a> <a href='javascript:bad()'>不安全链接</a></p><script>alert('bad')</script><img src='/ok.png' onerror='bad()'><img src='javascript:bad()'><img src='data:image/png;base64,AAAA'>",
                 "buyNotice":"请核对账号"}
                """);
        var product=new Product(); presentation.apply(product,"https://fixture.example/site/",snapshot);
        assertEquals("https://fixture.example/cover.png",product.getCoverUrl());
        assertEquals("安全简介",product.getDescription());
        assertTrue(product.getDetailMd().contains("## 说明"));
        assertTrue(product.getDetailMd().contains("**安全文字**"));
        assertTrue(product.getDetailMd().contains("[帮助](https://fixture.example/help)"));
        assertTrue(product.getDetailMd().contains("https://fixture.example/ok.png"));
        assertTrue(product.getDetailMd().contains("购买提醒"));
        assertFalse(product.getDetailMd().contains("<script"));
        assertFalse(product.getDetailMd().contains("onerror"));
        assertFalse(product.getDetailMd().contains("javascript:"));
        assertFalse(product.getDetailMd().contains("data:image"));
    }

    @Test void preservesMarkdownAndRejectsUnsafeCoverWithoutTouchingOtherFields() throws Exception {
        var product=new Product(); product.setTitle("我的名称"); product.setDescription("保留简介");
        presentation.apply(product,"https://fixture.example",json.readTree("{\"imgUrl\":\"javascript:alert(1)\",\"goodsDetail\":\"## 说明\\n[安全链接](https://example.test)\"}"));
        assertNull(product.getCoverUrl()); assertEquals("我的名称",product.getTitle()); assertEquals("保留简介",product.getDescription());
        assertEquals("## 说明\n[安全链接](https://example.test)",product.getDetailMd());
    }
}
