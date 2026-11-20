package com.myshop.supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.exception.BusinessException;
import com.myshop.model.CustomerInputField;
import com.myshop.supplier.kayixin.KayixinRechargeTemplates;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SupplierRechargeFieldsTest {
    private final ObjectMapper json=new ObjectMapper();
    private final KayixinRechargeTemplates parser=new KayixinRechargeTemplates();
    private final SupplierRechargeFields rules=new SupplierRechargeFields();
    @Test void validatesSupportedFieldsAndSubmitsOptionValue() throws Exception {
        var fields=parser.parse(json.readTree("""
          {"rechargeTemplates":[
            {"type":0,"title":"账号","required":1,"regex":"^[a-z]{2,5}$"},
            {"type":13,"title":"数量","required":1},
            {"type":14,"title":"区服","required":1,"options":[{"name":"一区","value":"zone-1"}]},
            {"type":15,"title":"备注","required":0}
          ]}
          """));
        assertTrue(rules.supported(fields));
        assertEquals(List.of(CustomerInputField.Type.TEXT,CustomerInputField.Type.NUMBER,
                CustomerInputField.Type.SELECT,CustomerInputField.Type.MULTILINE),fields.stream().map(CustomerInputField::type).toList());
        var inputs=List.of(Map.of("id",fields.get(0).id(),"value","abc"),
                Map.of("id",fields.get(1).id(),"value","123"),
                Map.of("id",fields.get(2).id(),"value","zone-1"),
                Map.of("id",fields.get(3).id(),"value","first\nsecond"));
        var result=rules.validate(fields,inputs);
        assertEquals("zone-1",result.attach().get(2).value()); assertEquals("区服",result.attach().get(2).name());
        assertEquals("first\nsecond",result.values().get(3).value());
        assertThrows(BusinessException.class,()->rules.validate(fields,List.of(inputs.get(0),inputs.get(1),
                Map.of("id",fields.get(2).id(),"value","一区"),inputs.get(3))));
        assertThrows(BusinessException.class,()->rules.validate(fields,List.of(Map.of("id",fields.get(0).id(),"value","123"),inputs.get(1),inputs.get(2),inputs.get(3))));
        assertThrows(BusinessException.class,()->rules.validate(fields,List.of(inputs.get(0),Map.of("id",fields.get(1).id(),"value","NaN"),inputs.get(2),inputs.get(3))));
    }
    @Test void unsafeOrUnverifiedWireFormatsAreNotSellable() throws Exception {
        var image=parser.parse(json.readTree("{\"rechargeTemplates\":[{\"type\":1,\"title\":\"图片\",\"required\":1}]}"));
        var cascade=parser.parse(json.readTree("{\"rechargeTemplates\":[{\"type\":16,\"title\":\"地区\",\"required\":1,\"options\":[{\"name\":\"省\",\"value\":\"p\",\"children\":[{\"name\":\"市\",\"value\":\"c\"}]}]}]}"));
        var unsafe=parser.parse(json.readTree("{\"rechargeTemplates\":[{\"type\":0,\"title\":\"账号\",\"required\":1,\"regex\":\"(a+)+\"}]}"));
        assertFalse(rules.supported(image)); assertFalse(rules.supported(cascade)); assertFalse(rules.supported(unsafe));
        assertThrows(BusinessException.class,()->rules.validate(image,List.of()));
    }
    @Test void fieldIdIsStableAcrossJsonObjectPropertyOrder() throws Exception {
        var first=parser.parse(json.readTree("{\"rechargeTemplates\":[{\"type\":0,\"title\":\"账号\",\"required\":1}]}"));
        var reordered=parser.parse(json.readTree("{\"rechargeTemplates\":[{\"required\":1,\"title\":\"账号\",\"type\":0}]}"));
        assertEquals(first.getFirst().id(),reordered.getFirst().id());
    }
}
