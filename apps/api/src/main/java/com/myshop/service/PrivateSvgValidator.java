package com.myshop.service;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Set;

/** A conservative SVG subset for private evidence; never fetches external resources. */
public final class PrivateSvgValidator {
    private PrivateSvgValidator() {}
    public static boolean safe(byte[] data) {
        try {
            var f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            f.setXIncludeAware(false); f.setExpandEntityReferences(false);
            var builder=f.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            var doc=builder.parse(new ByteArrayInputStream(data));
            var root=doc.getDocumentElement();
            if (!"svg".equals(root.getLocalName()) || !"http://www.w3.org/2000/svg".equals(root.getNamespaceURI())) return false;
            var nodes=new java.util.ArrayDeque<org.w3c.dom.Node>(); nodes.add(doc);
            while (!nodes.isEmpty()) {
                var node=nodes.removeFirst();
                if (node.getNodeType()==org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE
                        || node.getNodeType()==org.w3c.dom.Node.ENTITY_REFERENCE_NODE) return false;
                var children=node.getChildNodes();
                for (int i=0;i<children.getLength();i++) nodes.addLast(children.item(i));
            }
            Set<String> allowed=Set.of("svg","g","path","rect","circle","ellipse","line","polyline","polygon",
                    "text","tspan","title","desc","defs","linearGradient","radialGradient","stop","clipPath","mask");
            var all=root.getElementsByTagName("*");
            for (int i=-1;i<all.getLength();i++) {
                var node=i<0 ? root : all.item(i); String name=node.getLocalName()==null ? node.getNodeName() : node.getLocalName();
                if (!allowed.contains(name) || !"http://www.w3.org/2000/svg".equals(node.getNamespaceURI())) return false;
                var attrs=node.getAttributes();
                for (int j=0;j<attrs.getLength();j++) {
                    var a=attrs.item(j); String n=a.getNodeName().toLowerCase(Locale.ROOT),v=a.getNodeValue().toLowerCase(Locale.ROOT);
                    if (n.startsWith("on") || n.equals("style") || ((n.equals("href") || n.endsWith(":href")) && !v.startsWith("#")) || v.contains("url(")) return false;
                }
            }
            return true;
        } catch (Exception ignored) { return false; }
    }
}
