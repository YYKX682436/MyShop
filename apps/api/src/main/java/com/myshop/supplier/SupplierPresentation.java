package com.myshop.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.myshop.entity.Product;
import org.springframework.stereotype.Component;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.Locale;

/** Converts supplier presentation into the existing, HTML-disabled Markdown renderer. */
@Component
public class SupplierPresentation {
    public void apply(Product product, String baseUrl, JsonNode snapshot) {
        if (snapshot == null || !snapshot.isObject()) return;
        if (snapshot.has("imgUrl")) product.setCoverUrl(url(baseUrl, text(snapshot, "imgUrl"), 255));
        if (snapshot.has("goodsDescribe")) product.setDescription(plain(text(snapshot, "goodsDescribe")));
        if (snapshot.has("goodsDetail") || snapshot.has("buyNotice")) {
            String detail = markdown(baseUrl, text(snapshot, "goodsDetail"));
            String notice = markdown(baseUrl, text(snapshot, "buyNotice"));
            product.setDetailMd((detail + (notice.isBlank() ? "" : "\n\n## 购买提醒\n\n" + notice)).strip());
        }
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual() || value.textValue().length() > 200_000)
            throw new SupplierException("DATA", "上游商品资料格式或长度无效");
        return value.textValue();
    }

    private String plain(String input) {
        if (!looksLikeHtml(input)) return input.strip();
        return parse("", input, false).strip();
    }

    private String markdown(String base, String input) {
        if (!looksLikeHtml(input)) return input.strip();
        return parse(base, input, true).strip();
    }

    private boolean looksLikeHtml(String input) {
        return input.matches("(?s).*<\\s*/?\\s*[A-Za-z][^>]*>.*");
    }

    private String parse(String base, String html, boolean markdown) {
        StringBuilder out = new StringBuilder();
        try {
            new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
                private boolean suppressed;
                private String link;
                @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) { suppressed = true; return; }
                    if (!suppressed && (tag == HTML.Tag.P || tag == HTML.Tag.DIV || tag == HTML.Tag.LI
                            || tag == HTML.Tag.H1 || tag == HTML.Tag.H2 || tag == HTML.Tag.H3)) out.append("\n\n");
                    if (!suppressed && markdown && tag == HTML.Tag.LI) out.append("- ");
                    if (suppressed || !markdown) return;
                    if (tag == HTML.Tag.H1) out.append("# ");
                    if (tag == HTML.Tag.H2) out.append("## ");
                    if (tag == HTML.Tag.H3) out.append("### ");
                    if (tag == HTML.Tag.B || tag == HTML.Tag.STRONG) out.append("**");
                    if (tag == HTML.Tag.I || tag == HTML.Tag.EM) out.append("*");
                    if (tag == HTML.Tag.A) {
                        Object href=attrs.getAttribute(HTML.Attribute.HREF);
                        link=url(base,href==null ? "" : href.toString(),2048);
                        if (link!=null) out.append('[');
                    }
                }
                @Override public void handleEndTag(HTML.Tag tag, int pos) {
                    if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) { suppressed = false; return; }
                    if (suppressed || !markdown) return;
                    if (tag == HTML.Tag.A && link!=null) { out.append("](").append(link).append(')'); link=null; }
                    if (tag == HTML.Tag.B || tag == HTML.Tag.STRONG) out.append("**");
                    if (tag == HTML.Tag.I || tag == HTML.Tag.EM) out.append("*");
                }
                @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    if (suppressed) return;
                    if (tag == HTML.Tag.BR) out.append('\n');
                    if (markdown && tag == HTML.Tag.IMG) {
                        Object src = attrs.getAttribute(HTML.Attribute.SRC);
                        String safe = url(base, src == null ? "" : src.toString(), 2048);
                        if (safe != null) out.append("\n\n![商品图片](").append(safe).append(")\n\n");
                    }
                }
                @Override public void handleText(char[] data, int pos) {
                    if (!suppressed) out.append(markdown ? escape(new String(data)) : new String(data));
                }
            }, true);
        } catch (IOException e) { throw new SupplierException("DATA", "上游商品资料无法解析"); }
        return out.toString();
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
                .replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private String url(String base, String raw, int limit) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(base).resolve(raw.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null || uri.getUserInfo() != null)
                return null;
            String result = uri.toASCIIString().replace("(", "%28").replace(")", "%29");
            return result.length() <= limit ? result : null;
        } catch (IllegalArgumentException e) { return null; }
    }
}
