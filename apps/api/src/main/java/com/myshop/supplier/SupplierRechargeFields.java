package com.myshop.supplier;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import com.myshop.model.CustomerInputField;
import com.myshop.model.CustomerInputValue;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Validate provider-neutral customer fields independently of the browser. */
@Component
public class SupplierRechargeFields {
    /** Image and cascade wire formats are not verified; complicated regexes fail closed. */
    public boolean supported(List<CustomerInputField> fields) {
        return fields.stream().noneMatch(f -> f.type()==null || f.type()==CustomerInputField.Type.IMAGE
                || f.type()==CustomerInputField.Type.CASCADE || !safeRegex(f.regex()));
    }

    public Inputs validate(List<CustomerInputField> fields,Object raw) {
        if (!supported(fields)) throw bad("该商品包含尚未验证的充值字段，暂不能购买");
        if (fields.isEmpty()) {
            if (raw==null || raw instanceof List<?> list && list.isEmpty()) return new Inputs(List.of(),List.of());
            throw bad("该商品无需填写充值信息");
        }
        if (!(raw instanceof List<?> list) || list.size()!=fields.size()) throw bad("请完整填写充值信息");
        Map<String,String> values=new HashMap<>();
        for (Object entry:list) {
            String id; Object rawValue;
            if (entry instanceof Map<?,?> m && m.get("id") instanceof String key) { id=key; rawValue=m.get("value"); }
            else if (entry instanceof CustomerInputValue input) { id=input.id(); rawValue=input.value(); }
            else throw bad("充值信息格式无效");
            if (!(rawValue instanceof String value) || values.putIfAbsent(id,value)!=null) throw bad("充值信息格式无效");
        }
        List<CustomerInputValue> snapshots=new ArrayList<>(); List<SupplierModels.Attachment> attach=new ArrayList<>();
        for (var field:fields) {
            String value=values.remove(field.id());
            if (value==null) throw bad("充值字段已变化，请刷新商品后重试");
            value=value.strip();
            if (value.isEmpty() && Boolean.TRUE.equals(field.required())) throw bad("请填写"+field.label());
            if (value.length()>255 || value.chars().anyMatch(c -> Character.isISOControl(c)
                    && !(field.type()==CustomerInputField.Type.MULTILINE && (c=='\n' || c=='\r' || c=='\t')))) throw bad(field.label()+"格式无效");
            if (!value.isEmpty()) {
                if (field.type()==CustomerInputField.Type.NUMBER && !value.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) throw bad(field.label()+"必须是数字");
                if (field.type()==CustomerInputField.Type.SELECT) {
                    boolean found=false;
                    for (var option:field.options()) if (option.value().equals(value)) found=true;
                    if (!found) throw bad(field.label()+"选项无效");
                }
                if (field.regex()!=null && !field.regex().isBlank() && !Pattern.matches(field.regex(),value)) throw bad(field.label()+"格式无效");
            }
            snapshots.add(new CustomerInputValue(field.id(),field.label(),value));
            attach.add(new SupplierModels.Attachment(field.label(),value));
        }
        if (!values.isEmpty()) throw bad("充值字段已变化，请刷新商品后重试");
        return new Inputs(List.copyOf(snapshots),List.copyOf(attach));
    }

    private boolean safeRegex(String regex) {
        if (regex==null || regex.isBlank()) return true;
        // A conservative subset avoids backtracking explosions from upstream patterns.
        if (regex.length()>255 || regex.matches(".*[()|].*") || regex.contains("\\1")
                || regex.chars().filter(c -> c=='*' || c=='+' || c=='?' || c=='{').count()>1) return false;
        for (int i=0;i<regex.length();i++) {
            if (regex.charAt(i)=='\\') {
                if (++i==regex.length() || "dDsSwW\\.^$?*+[]{}-/".indexOf(regex.charAt(i))<0) return false;
            }
        }
        try { Pattern.compile(regex); return true; } catch (PatternSyntaxException e) { return false; }
    }
    private BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST,message); }
    public record Inputs(List<CustomerInputValue> values,List<SupplierModels.Attachment> attach) {
        @Override public String toString() { return "SupplierInputs[redacted]"; }
    }
}
