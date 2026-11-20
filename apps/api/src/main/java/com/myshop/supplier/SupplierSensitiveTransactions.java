package com.myshop.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.SupplierSensitiveOperation;
import com.myshop.repository.SupplierAccountRepository;
import com.myshop.repository.SupplierSensitiveOperationRepository;
import com.myshop.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Short database-only boundaries; the committed claim is never repeated. */
@Service @RequiredArgsConstructor
public class SupplierSensitiveTransactions {
    private final SupplierAccountRepository accounts;
    private final SupplierSensitiveOperationRepository operations;
    private final SupplierSecretCipher cipher;
    private final OperationLogService audit;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public UUID intent(UUID accountId,String kind,UUID key,UUID operator,JsonNode parameters) {
        var account=accounts.findForUpdate(accountId).orElseThrow(() -> invalid("货源账号不存在"));
        var existing=operations.findByAccountIdAndIdempotencyKey(accountId,key).orElse(null);
        if (existing!=null) {
            if (!existing.getKind().equals(kind) || !existing.getOperatorId().equals(operator)
                    || !existing.getParameters().equals(parameters)) throw invalid("操作标识已用于其他请求");
            return existing.getId();
        }
        if (!account.isEnabled()) throw invalid("货源账号已停用");
        if (operations.hasUnresolved(accountId,kind)) throw invalid("该货源已有未解决的同类操作，请先人工核实");
        SupplierSensitiveOperation op=new SupplierSensitiveOperation();
        op.setAccountId(accountId); op.setKind(kind); op.setIdempotencyKey(key); op.setOperatorId(operator);
        op.setAccountNameSnapshot(account.getName()); op.setProviderTypeSnapshot(account.getProviderType());
        op.setBaseUrlSnapshot(account.getBaseUrl()); op.setAppIdSnapshot(account.getAppId());
        op.setStatus("INTENT"); op.setParameters(parameters);
        if ("TRANSFER".equals(kind)) op.setOuterNumber("MST"+UUID.randomUUID().toString().replace("-",""));
        operations.saveAndFlush(op);
        audit.logCurrentRequest("supplier."+kind.toLowerCase()+".intent","SUPPLIER_OPERATION",op.getId().toString(),kind+" 操作已创建");
        return op.getId();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public boolean claim(UUID id,SupplierService.RuntimeAccount prepared) {
        // Do not load the entity before waiting for the account lock: the
        // persistence context would retain its stale INTENT state afterward.
        UUID accountId=operations.findAccountId(id).orElseThrow();
        var account=accounts.findForUpdate(accountId).orElseThrow();
        var op=operations.findForUpdate(id).orElseThrow();
        if (!"INTENT".equals(op.getStatus())) return false;
        var c=prepared.connection();
        boolean sameSecret=MessageDigest.isEqual(cipher.decrypt(account.getEncryptedSecret()).getBytes(StandardCharsets.UTF_8),
                c.secret().getBytes(StandardCharsets.UTF_8));
        if (!account.isEnabled() || !account.getProviderType().equals(op.getProviderTypeSnapshot())
                || !account.getBaseUrl().equals(op.getBaseUrlSnapshot()) || !account.getAppId().equals(op.getAppIdSnapshot())
                || !account.getProviderType().equals(prepared.provider().type())
                || !account.getBaseUrl().equals(c.baseUrl()) || !account.getAppId().equals(c.appId()) || !sameSecret) {
            op.setStatus("REJECTED"); op.setErrorCode("ACCOUNT_CHANGED"); return false;
        }
        op.setStatus("SUBMITTING"); op.setSubmittedAt(LocalDateTime.now()); op.setErrorCode(null);
        return true;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void rejectBeforeSend(UUID id,String error) {
        var op=operations.findForUpdate(id).orElseThrow();
        if ("INTENT".equals(op.getStatus())) { op.setStatus("REJECTED"); op.setErrorCode(safe(error)); }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void unknown(UUID id,String error) {
        var op=operations.findForUpdate(id).orElseThrow();
        if ("SUBMITTING".equals(op.getStatus())) { op.setStatus("UNKNOWN"); op.setErrorCode(safe(error)); }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void succeeded(UUID id,List<SupplierModels.Cdkey> codes) {
        var op=operations.findForUpdate(id).orElseThrow();
        if (!"SUBMITTING".equals(op.getStatus())) throw invalid("操作结果无法确认");
        if ("CDKEY".equals(op.getKind())) {
            if (codes==null || codes.isEmpty()) throw invalid("兑换码结果不完整");
            try { op.setEncryptedResult(cipher.encrypt(json.writeValueAsString(codes))); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid("兑换码结果无法保存"); }
        } else if (codes!=null) throw invalid("操作结果无效");
        op.setStatus("SUCCEEDED"); op.setErrorCode(null);
        operations.saveAndFlush(op);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void review(UUID id,UUID accountId,String kind,UUID operator,String outcome) {
        var op=operations.findForUpdate(id).filter(o -> o.getAccountId().equals(accountId) && o.getKind().equals(kind))
                .orElseThrow(() -> invalid("操作记录不存在"));
        if (!"UNKNOWN".equals(op.getStatus())) throw invalid("当前操作不需要人工核实");
        if (!Set.of("CONTACTED_SUPPLIER","MANUAL_HANDLING","RESULT_UNCONFIRMED").contains(outcome)) throw invalid("核实结果无效");
        op.setReviewOutcome(outcome); op.setReviewedBy(operator); op.setReviewedAt(LocalDateTime.now());
        audit.logCurrentRequest("supplier.operation.review","SUPPLIER_OPERATION",id.toString(),outcome);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void cancelIntent(UUID id,UUID accountId,String kind) {
        accounts.findForUpdate(accountId).orElseThrow(() -> invalid("货源账号不存在"));
        var op=operations.findForUpdate(id).filter(o -> o.getAccountId().equals(accountId) && o.getKind().equals(kind))
                .orElseThrow(() -> invalid("操作记录不存在"));
        if (!"INTENT".equals(op.getStatus())) throw invalid("操作已认领，不能取消或重复提交");
        op.setStatus("REJECTED"); op.setErrorCode("CANCELLED");
        audit.logCurrentRequest("supplier.operation.cancel","SUPPLIER_OPERATION",id.toString(),"取消未提交意图");
    }
    private String safe(String error) { return error!=null && error.matches("[A-Z_]{1,64}") ? error : "INTERNAL"; }
    private SupplierException invalid(String message) { return new SupplierException("CONFIG",message); }
}
