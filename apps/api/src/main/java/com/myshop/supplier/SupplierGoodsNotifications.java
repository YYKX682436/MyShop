package com.myshop.supplier;

import com.myshop.repository.SupplierProductMappingRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable notifications are hints; only a fresh provider detail may update a snapshot. */
@Service
public class SupplierGoodsNotifications {
    private final JdbcTemplate jdbc;
    private final SupplierProductMappingRepository mappings;
    private final SupplierService suppliers;
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(200), task -> {
        Thread t=new Thread(task,"supplier-goods-notification"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean scanning=new AtomicBoolean();

    public SupplierGoodsNotifications(JdbcTemplate jdbc,SupplierProductMappingRepository mappings,SupplierService suppliers) {
        this.jdbc=jdbc; this.mappings=mappings; this.suppliers=suppliers;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public UUID accept(UUID account,String goodsId,String hash) {
        UUID id=UUID.randomUUID();
        int inserted=jdbc.update("""
                INSERT INTO supplier_goods_notifications(id,account_id,remote_goods_id,event_hash,status)
                VALUES (?,?,?,?,'PENDING') ON CONFLICT(account_id,event_hash) DO NOTHING
                """,id,account,goodsId,hash);
        if (inserted==1) return id;
        return jdbc.queryForObject("SELECT id FROM supplier_goods_notifications WHERE account_id=? AND event_hash=?",
                UUID.class,account,hash);
    }
    public void enqueue(UUID id) {
        try { workers.execute(() -> process(id)); } catch (RejectedExecutionException ignored) { }
    }
    @Scheduled(fixedDelayString="${supplier.goods-notification-interval-ms:30000}",initialDelayString="${supplier.sync-initial-delay-ms:120000}")
    public void recover() {
        if (!scanning.compareAndSet(false,true)) return;
        try {
            var ids=jdbc.queryForList("""
                    SELECT id FROM supplier_goods_notifications WHERE status='PENDING'
                    ORDER BY COALESCE(last_attempt_at,created_at),created_at LIMIT 20
                    """,UUID.class);
            var tasks=ids.stream().map(id -> CompletableFuture.runAsync(() -> process(id),workers)
                    .exceptionally(ignored -> null)).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(tasks).whenComplete((done,error) -> scanning.set(false));
        } catch (RuntimeException ignored) { scanning.set(false); }
    }
    public void process(UUID id) {
        try {
            var rows=jdbc.query("SELECT account_id,remote_goods_id FROM supplier_goods_notifications WHERE id=? AND status='PENDING'",
                    (rs,n) -> new Target(rs.getObject(1,UUID.class),rs.getString(2)),id);
            if (rows.isEmpty()) return;
            Target target=rows.getFirst();
            jdbc.update("UPDATE supplier_goods_notifications SET last_attempt_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",id);
            if (mappings.findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(target.account(),target.goods()).isEmpty()) {
                jdbc.update("UPDATE supplier_goods_notifications SET status='IGNORED',error_code=NULL WHERE id=? AND status='PENDING'",id);
                return;
            }
            suppliers.sync(target.account(),target.goods());
            jdbc.update("UPDATE supplier_goods_notifications SET status='SYNCED',error_code=NULL WHERE id=? AND status='PENDING'",id);
        } catch (RuntimeException e) {
            String kind=e instanceof SupplierException s ? s.kind() : "INTERNAL";
            if (kind==null || !kind.matches("[A-Z_]{1,64}")) kind="INTERNAL";
            jdbc.update("UPDATE supplier_goods_notifications SET error_code=?,last_attempt_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",kind,id);
        }
    }
    @PreDestroy public void stop() { workers.shutdownNow(); }
    private record Target(UUID account,String goods) {}
}
