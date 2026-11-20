package com.myshop.supplier.kayixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.myshop.model.CustomerInputField;
import com.myshop.supplier.SupplierException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Parses the Kayixin-specific template shape at the provider boundary. */
public class KayixinRechargeTemplates {
    public List<CustomerInputField> parse(JsonNode snapshot) {
        JsonNode templates=snapshot.path("rechargeTemplates");
        if (templates.isMissingNode() || templates.isNull()) return List.of();
        if (!templates.isArray() || templates.size()>20) throw invalid();
        List<CustomerInputField> fields=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for (JsonNode field:templates) {
            if (!field.isObject() || !field.path("type").isInt() || !field.path("required").isInt()) throw invalid();
            int type=field.path("type").asInt(), required=field.path("required").asInt();
            if (!Set.of(0,1,13,14,15,16).contains(type) || (required!=0 && required!=1)) throw invalid();
            String title=string(field,"title",true,50);
            String placeholder=string(field,"placeholder",false,255);
            String regex=string(field,"regex",false,255);
            List<CustomerInputField.Option> options=null;
            if (type==14 || type==16) {
                JsonNode nodes=field.path("options");
                if (!nodes.isArray() || nodes.isEmpty() || nodes.size()>200) throw invalid();
                options=new ArrayList<>(); Set<String> values=new HashSet<>();
                for (JsonNode option:nodes) {
                    String name=string(option,"name",true,255), value=string(option,"value",true,255);
                    if (!values.add(value)) throw invalid();
                    options.add(new CustomerInputField.Option(name,value));
                }
                options=List.copyOf(options);
            }
            String id=fieldId(field);
            if (!seen.add(id)) throw invalid();
            CustomerInputField.Type normalized=switch (type) {
                case 0 -> CustomerInputField.Type.TEXT;
                case 1 -> CustomerInputField.Type.IMAGE;
                case 13 -> CustomerInputField.Type.NUMBER;
                case 14 -> CustomerInputField.Type.SELECT;
                case 15 -> CustomerInputField.Type.MULTILINE;
                case 16 -> CustomerInputField.Type.CASCADE;
                default -> throw invalid();
            };
            fields.add(new CustomerInputField(id,title,normalized,required==1,placeholder,regex,options));
        }
        return List.copyOf(fields);
    }

    private String string(JsonNode node,String key,boolean required,int max) {
        JsonNode value=node.get(key);
        if (value==null || value.isNull()) { if (required) throw invalid(); return null; }
        if (!value.isTextual() || value.asText().length()>max || required && value.asText().isBlank()) throw invalid();
        return value.asText();
    }
    private String fieldId(JsonNode node) {
        try { return "s-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical(node).toString().getBytes(StandardCharsets.UTF_8))).substring(0,24); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            List<String> names=new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); Collections.sort(names);
            for (String name:names) result.set(name,canonical(node.get(name)));
            return result;
        }
        if (node.isArray()) {
            var result=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            for (JsonNode value:node) result.add(canonical(value));
            return result;
        }
        return node;
    }
    private SupplierException invalid() { return new SupplierException("DATA","供应商充值模板无效"); }
}
