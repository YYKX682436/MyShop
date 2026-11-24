package com.myshop.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownMediaCleanerTest {
    private static final String FILE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png";
    private static final String PATH = "/api/uploads/" + FILE;
    private static final String SITE = "https://shop.example";

    @Test void removesParsedImagesAndReferenceDefinitionsWithoutReformattingOtherContent() {
        String original = "标题\n第二行\n\n- 第一项 ![图](" + PATH + ")\n"
                + "- [![关联图][picture]](https://elsewhere.example/page)\n"
                + "- [保留链接](https://elsewhere.example/" + FILE + ")\n\n"
                + "| 内容 | 图片 |\n| --- | --- |\n| 说明 | ![图](" + PATH + "?v=1) |\n\n"
                + "[picture]: https://shop.example" + PATH + "\n";
        String expected = "标题\n第二行\n\n- 第一项 （资源已删除）\n"
                + "- [（资源已删除）](https://elsewhere.example/page)\n"
                + "- [保留链接](https://elsewhere.example/" + FILE + ")\n\n"
                + "| 内容 | 图片 |\n| --- | --- |\n| 说明 | （资源已删除） |\n\n\n";
        assertEquals(expected, MarkdownMediaCleaner.remove(original, PATH, SITE));
    }

    @Test void removesDirectLinksButDoesNotTouchCodeOrExternalHosts() {
        String original = "[原图](" + PATH + ")\n`![示例](" + PATH + ")`\n"
                + "![外站](https://outside.example" + PATH + ")";
        String expected = "（资源已删除）\n`![示例](" + PATH + ")`\n"
                + "![外站](https://outside.example" + PATH + ")";
        assertEquals(expected, MarkdownMediaCleaner.remove(original, PATH, SITE));
    }

    @Test void understandsMarkdownPunctuationEscapes() {
        String source = "前文 ![图](" + PATH.replace(".png", "\\.png") + ") 后文";
        assertEquals("前文 （资源已删除） 后文", MarkdownMediaCleaner.remove(source, PATH, SITE));
    }

    @Test void keepsListAndQuoteStructureWhenImageSpansLines() {
        String source = "> 说明\n> - ![第一行\n>   第二行](" + PATH + ")\n> - 后续内容\n";
        String cleaned = MarkdownMediaCleaner.remove(source, PATH, SITE);
        assertEquals("> 说明\n> - （资源已删除）\n>   \n> - 后续内容\n", cleaned);
    }

    @Test void unlinksDeletedResourceButKeepsAnotherImageAndItsSurroundingLabel() {
        String source = "前文 [描述 `]` ![保留](/api/uploads/other.png) 尾部](" + PATH + ") 后文";
        assertEquals("前文 描述 `]` ![保留](/api/uploads/other.png) 尾部（资源已删除） 后文",
                MarkdownMediaCleaner.remove(source, PATH, SITE));
    }

    @Test void keepsNestedImageAcrossLinesAndReferenceStyleLinks() {
        String source = "> - [描述\n>    ![保留][other]\n>    结束][removed]\n> - 后续\n\n"
                + "[other]: /api/uploads/other.png\n[removed]: " + PATH + "\n";
        String expected = "> - 描述\n>    ![保留][other]\n>    结束（资源已删除）\n> - 后续\n\n"
                + "[other]: /api/uploads/other.png\n\n";
        assertEquals(expected, MarkdownMediaCleaner.remove(source, PATH, SITE));
    }

    @Test void nestedTargetImageIsRemovedEvenWhenAnotherChildImageIsRetained() {
        String source = "[![删除](" + PATH + ") 和 ![保留](/api/uploads/other.png)](" + PATH + ")";
        assertEquals("（资源已删除） 和 ![保留](/api/uploads/other.png)（资源已删除）",
                MarkdownMediaCleaner.remove(source, PATH, SITE));
    }
}
