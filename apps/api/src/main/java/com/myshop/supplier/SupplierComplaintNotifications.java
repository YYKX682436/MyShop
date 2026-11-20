package com.myshop.supplier;

import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.entity.SupplierAftercareOperation;
import com.myshop.entity.SupplierPurchase;
import com.myshop.exception.BusinessException;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.SupplierAftercareOperationRepository;
import com.myshop.repository.SupplierPurchaseRepository;
import com.myshop.service.PrivateUploadStorage;
import com.myshop.service.PrivateSvgValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Correlates a signed complaint notification and stores its evidence privately. */
@Service @RequiredArgsConstructor
public class SupplierComplaintNotifications {
    private final JdbcTemplate jdbc;
    private final SupplierAftercareOperationRepository operations;
    private final SupplierPurchaseRepository purchases;
    private final OrderRepository orders;
    private final PrivateUploadStorage storage;

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=10)
    public void accept(UUID account,long reqId,String orderNumber,String outer,int status,String reply,String hash,List<String> encoded) {
        List<Evidence> evidence=validateEvidence(encoded);
        UUID operationId=operations.findComplaintId(account,reqId).orElseThrow(this::invalid);
        UUID purchaseId=operations.findPurchaseId(operationId).orElseThrow(this::invalid);
        UUID orderId=purchases.findOrderId(purchaseId).orElseThrow(this::invalid);
        orders.findByIdForUpdate(orderId).orElseThrow(this::invalid);
        SupplierPurchase p=purchases.findForUpdate(purchaseId).orElseThrow(this::invalid);
        SupplierAftercareOperation op=operations.findForUpdate(operationId).orElseThrow(this::invalid);
        if (!p.getAccountId().equals(account) || !Objects.equals(p.getUpstreamOrderNumber(),orderNumber)
                || (outer!=null && !outer.isBlank() && !p.getOuterNumber().equals(outer))
                || op.getAfterSaleId()==null || !Objects.equals(op.getSupplierReqId(),reqId)) throw invalid();
        if (status<1 || status>4 || reply==null || reply.length()>20_000) throw invalid();
        List<SupplierAftercareOperation> siblings=operations.findByPurchaseIdOrderByCreatedAtAsc(purchaseId);
        if (siblings.stream().anyMatch(o -> o.getKind().equals("COMPLAINT") && o.getUpstreamStatus()!=null
                && (o.getUpstreamStatus()==3 || o.getUpstreamStatus()==4))) return;
        if (op.getUpstreamStatus()!=null && status<op.getUpstreamStatus()) return;
        UUID event=UUID.randomUUID();
        int inserted=jdbc.update("""
                INSERT INTO supplier_complaint_events(id,account_id,operation_id,supplier_req_id,event_hash,status,reply)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """,event,account,operationId,reqId,hash,status,reply);
        if (inserted==0) return;
        List<String> written=new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int outcome) {
                if (outcome!=TransactionSynchronization.STATUS_COMMITTED) written.forEach(storage::deleteManagedFile);
            }
        });
        for (Evidence item:evidence) {
            UUID id=UUID.randomUUID();
            String key="supplier-complaints/"+op.getAfterSaleId()+"/"+id+"."+item.extension();
            storage.write(key,new EvidenceFile(item.bytes(),item.extension(),item.mime()));
            written.add(key);
            jdbc.update("INSERT INTO supplier_complaint_attachments(id,event_id,storage_key,content_type,file_size) VALUES (?,?,?,?,?)",
                    id,event,key,item.mime(),item.bytes().length);
        }
        op.setUpstreamStatus(status); op.setResult(reply); op.setErrorCode(null);
        op.setStatus(status==3 || status==4 ? "COMPLETED" : "ACCEPTED");
    }
    public List<Map<String,Object>> forAfterSale(UUID afterSaleId) {
        return jdbc.query("""
            SELECT e.id,e.status,e.reply,e.created_at FROM supplier_complaint_events e
            JOIN supplier_aftercare_operations o ON o.id=e.operation_id
            WHERE o.after_sale_id=? ORDER BY e.created_at,e.id
            """,(rs,n) -> {
                UUID id=rs.getObject("id",UUID.class);
                Map<String,Object> row=new LinkedHashMap<>(); row.put("status",rs.getInt("status"));
                row.put("reply",rs.getString("reply")); row.put("created_at",rs.getTimestamp("created_at").toLocalDateTime());
                row.put("attachments",jdbc.query("SELECT id,content_type,file_size FROM supplier_complaint_attachments WHERE event_id=? ORDER BY id",
                        (a,i) -> Map.of("id",a.getObject(1,UUID.class),"content_type",a.getString(2),"file_size",a.getLong(3),
                                "url","/api/supplier-complaint-attachments/"+a.getObject(1,UUID.class)),id));
                return row;
            },afterSaleId);
    }
    public Download download(UUID id) {
        var user=RequestContext.get();
        if (user==null || user.getUserId()==null || user.getRole()==null) throw forbidden();
        var rows=jdbc.query("""
                SELECT a.storage_key,a.content_type,a.file_size,r.user_id FROM supplier_complaint_attachments a
                JOIN supplier_complaint_events e ON e.id=a.event_id
                JOIN supplier_aftercare_operations o ON o.id=e.operation_id
                JOIN after_sale_requests r ON r.id=o.after_sale_id
                JOIN orders ord ON ord.id=r.order_id AND ord.user_id=r.user_id
                WHERE a.id=?
                """,(rs,n) -> new Access(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getObject(4,UUID.class)),id);
        if (rows.isEmpty()) throw invalid();
        Access access=rows.getFirst();
        if (!Set.of("ADMIN","SUPER_ADMIN").contains(user.getRole())
                && !("USER".equals(user.getRole()) && user.getUserId().equals(access.owner()))) throw forbidden();
        try {
            var path=storage.resolveForRead(access.key());
            if (Files.size(path)!=access.size()) throw invalid();
            return new Download(Files.readAllBytes(path),access.mime());
        } catch (IOException | IllegalArgumentException e) { throw invalid(); }
    }
    public record Download(byte[] bytes,String contentType) { @Override public String toString(){return "Download[redacted]";} }
    private record Access(String key,String mime,long size,UUID owner) {}
    private record Evidence(byte[] bytes,String extension,String mime) { @Override public String toString(){return "Evidence[redacted]";} }
    public List<String> validateImages(List<String> encoded) {
        return validateEvidence(encoded).stream().map(Evidence::mime).toList();
    }
    private List<Evidence> validateEvidence(List<String> encoded) {
        if (encoded==null || encoded.size()>5) throw invalid();
        List<Evidence> files=new ArrayList<>(); long total=0;
        for (String value:encoded) {
            if (value==null || value.length()>14_000_000) throw invalid();
            final byte[] data;
            try { data=Base64.getDecoder().decode(value); } catch (IllegalArgumentException e) { throw invalid(); }
            total+=data.length;
            if (data.length==0 || data.length>10*1024*1024 || total>10*1024*1024) throw invalid();
            String ext,mime;
            if (starts(data,new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a})) { ext="png"; mime="image/png"; }
            else if (starts(data,new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff})) { ext="jpg"; mime="image/jpeg"; }
            else if (starts(data,"GIF87a".getBytes(StandardCharsets.US_ASCII)) || starts(data,"GIF89a".getBytes(StandardCharsets.US_ASCII))) { ext="gif"; mime="image/gif"; }
            else if (data.length>=12 && starts(data,"RIFF".getBytes(StandardCharsets.US_ASCII))
                    && new String(data,8,4,StandardCharsets.US_ASCII).equals("WEBP")) { ext="webp"; mime="image/webp"; }
            else if (PrivateSvgValidator.safe(data)) { ext="svg"; mime="image/svg+xml"; }
            else throw invalid();
            if (Set.of("png","jpg","gif").contains(ext) && !validRaster(data)) throw invalid();
            if (ext.equals("webp") && !validWebpContainer(data)) throw invalid();
            files.add(new Evidence(data,ext,mime));
        }
        return List.copyOf(files);
    }
    private boolean validRaster(byte[] bytes) {
        try (var input=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=javax.imageio.ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return false;
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                return width>0 && height>0 && (long)width*height<=20_000_000 && reader.read(0)!=null;
            } finally { reader.dispose(); }
        } catch (Exception ignored) { return false; }
    }
    private boolean validWebpContainer(byte[] data) {
        if (data.length<20) return false;
        long size=(data[4]&255L)|((data[5]&255L)<<8)|((data[6]&255L)<<16)|((data[7]&255L)<<24);
        if (size+8!=data.length) return false;
        int pos=12; boolean image=false;
        while (pos+8<=data.length) {
            String chunk=new String(data,pos,4,StandardCharsets.US_ASCII);
            long chunkSize=(data[pos+4]&255L)|((data[pos+5]&255L)<<8)|((data[pos+6]&255L)<<16)|((data[pos+7]&255L)<<24);
            long next=pos+8L+chunkSize+(chunkSize&1);
            if (next>data.length) return false;
            int start=pos+8;
            if (chunk.equals("VP8L")) {
                if (chunkSize<5 || (data[start]&255)!=0x2f) return false;
                image=true;
            } else if (chunk.equals("VP8 ")) {
                if (chunkSize<10 || (data[start+3]&255)!=0x9d || (data[start+4]&255)!=0x01 || (data[start+5]&255)!=0x2a) return false;
                image=true;
            } else if (chunk.equals("ANMF")) {
                if (chunkSize<16) return false;
                image=true;
            } else if (chunk.equals("VP8X") && chunkSize!=10) return false;
            pos=(int)next;
        }
        return image && pos==data.length;
    }
    private boolean starts(byte[] b,byte[] prefix) {
        if (b.length<prefix.length) return false;
        for (int i=0;i<prefix.length;i++) if (b[i]!=prefix[i]) return false;
        return true;
    }
    private BusinessException invalid() { return new BusinessException(ErrorCode.BAD_REQUEST,"供应商申诉通知或凭证无效"); }
    private BusinessException forbidden() { return new BusinessException(ErrorCode.FORBIDDEN,"无权访问此售后凭证"); }
    private record EvidenceFile(byte[] bytes,String extension,String mime) implements MultipartFile {
        public String getName(){return "evidence";} public String getOriginalFilename(){return "evidence."+extension;}
        public String getContentType(){return mime;} public boolean isEmpty(){return bytes.length==0;}
        public long getSize(){return bytes.length;} public byte[] getBytes(){return bytes;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public void transferTo(File dest) throws IOException { Files.write(dest.toPath(),bytes); }
        @Override public String toString(){return "EvidenceFile[redacted]";}
    }
}
