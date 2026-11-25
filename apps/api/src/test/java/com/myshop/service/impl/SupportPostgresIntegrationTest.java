package com.myshop.service.impl;

import com.myshop.context.RequestContext;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.exception.BusinessException;
import com.myshop.service.SupportService;
import com.myshop.service.SupportTimelineService;
import com.myshop.service.AfterSaleService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIf(value="postgresAvailable", disabledReason="Disposable PostgreSQL required")
@SpringBootTest
class SupportPostgresIntegrationTest {
    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static final Path ROOT = temporaryDirectory();
    static boolean postgresAvailable() { return POSTGRES.available(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("upload.path", () -> ROOT.resolve("public").toString());
        registry.add("private-upload.path", () -> ROOT.resolve("private").toString());
        registry.add("jwt.secret", () -> "support-integration-secret-with-at-least-sixty-four-characters-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("supplier.sync-initial-delay-ms", () -> "3600000");
    }
    @AfterAll static void cleanup() throws IOException {
        POSTGRES.close();
        if (Files.exists(ROOT)) try (var files=Files.walk(ROOT)) {
            for (Path path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    @AfterEach void clear() { RequestContext.clear(); SiteRequestContext.clear(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired SupportService support;
    @Autowired SupportTimelineService timeline;
    @Autowired AfterSaleService afterSales;
    @Autowired WebApplicationContext webContext;
    @Autowired FilterChainProxy security;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM support_system_events");
        jdbc.update("DELETE FROM support_read_states"); jdbc.update("DELETE FROM support_attachments");
        jdbc.update("DELETE FROM support_activity"); jdbc.update("DELETE FROM support_messages");
        jdbc.update("DELETE FROM support_conversations");
        jdbc.update("DELETE FROM after_sale_attachments"); jdbc.update("DELETE FROM after_sale_requests");
        jdbc.update("DELETE FROM orders");
    }

    @Test void guestAndAdministratorReadStatesAreIndependentAndRetriesAreIdempotent() {
        UUID key=UUID.randomUUID();
        Map<String,Object> sent=support.send(null,null,key,"你好",List.of(),false,"guest-a");
        UUID conversation=(UUID)sent.get("conversation_id");
        assertEquals(sent,support.send(null,null,key,"你好",List.of(),false,"guest-a"));
        assertThrows(BusinessException.class,() -> support.send(conversation,null,key,"不同内容",List.of(),false,"guest-a"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM support_messages",Integer.class));
        assertThrows(BusinessException.class,() -> support.detail(conversation,false,"guest-b"));
        assertEquals(0L,support.detail(conversation,false,"guest-a").get("unread"));
        assertEquals(0L,support.customerSummary("guest-a").get("unread"));

        UUID admin=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,is_deleted) VALUES (?,? ,?,'test','ADMIN',0)",admin,"admin-"+admin,admin+"@example.com");
        RequestContext.set(new RequestContext.UserInfo(admin,"admin","ADMIN"));
        assertEquals(1L,support.detail(conversation,true,null).get("unread"));
        assertEquals(true,support.detail(conversation,true,null).get("needs_attention"));
        assertTrue(isPending(conversation));
        var events=items(support.activities(conversation,true,null,null,null,40));
        long first=Long.parseLong((String)events.getFirst().get("cursor"));
        support.markRead(conversation,true,null,first);
        assertEquals(0L,support.detail(conversation,true,null).get("unread"));
        assertEquals(true,support.detail(conversation,true,null).get("needs_attention"));
        assertTrue(isPending(conversation));
        UUID adminKey=UUID.randomUUID();
        support.send(conversation,null,adminKey,"客服回复",List.of(),true,null);
        assertEquals(false,support.detail(conversation,true,null).get("needs_attention"));
        assertFalse(isPending(conversation));
        RequestContext.clear();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            support.send(conversation,null,UUID.randomUUID(),"回滚的咨询",List.of(),false,"guest-a");
            status.setRollbackOnly();
        });
        assertEquals(false,support.detail(conversation,false,"guest-a").get("needs_attention"));
        assertEquals(1L,support.detail(conversation,false,"guest-a").get("unread"));
        assertEquals(1L,support.customerSummary("guest-a").get("unread"));
        var next=items(support.activities(conversation,false,"guest-a",first,null,40));
        assertEquals(1,next.size());
        long second=Long.parseLong((String)next.getFirst().get("cursor"));
        support.markRead(conversation,false,"guest-a",second);
        support.markRead(conversation,false,"guest-a",first);
        assertEquals(0L,support.detail(conversation,false,"guest-a").get("unread"));
        assertEquals(second,jdbc.queryForObject("SELECT read_seq FROM support_read_states WHERE conversation_id=? AND viewer_type='CUSTOMER'",Long.class,conversation));

        support.send(conversation,null,UUID.randomUUID(),"再次咨询",List.of(),false,"guest-a");
        RequestContext.set(new RequestContext.UserInfo(admin,"admin","ADMIN"));
        assertEquals(true,support.detail(conversation,true,null).get("needs_attention"));
        assertTrue(isPending(conversation));
        // Replaying an older successful reply, system events, validation failures,
        // and rolled-back replies cannot resolve a later customer message.
        support.send(conversation,null,adminKey,"客服回复",List.of(),true,null);
        jdbc.update("INSERT INTO support_activity(conversation_id,source_type,source_id,notify_customer,notify_admin) VALUES (?,'SUPPLIER_UPDATE',?,true,true)",conversation,UUID.randomUUID());
        assertThrows(BusinessException.class,() -> support.send(conversation,null,UUID.randomUUID(),"",List.of(),true,null));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            support.send(conversation,null,UUID.randomUUID(),"回滚的回复",List.of(),true,null);
            status.setRollbackOnly();
        });
        assertEquals(true,support.detail(conversation,true,null).get("needs_attention"));
        assertTrue(isPending(conversation));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM support_messages WHERE content='回滚的回复'",Integer.class));
        RequestContext.clear();
        Subsite other=new Subsite(); other.setId(UUID.randomUUID()); SiteRequestContext.set(other);
        assertThrows(BusinessException.class,() -> support.detail(conversation,false,"guest-a"));
        assertTrue(support.list(false,"guest-a",null,null,false,false,0,30).isEmpty());
    }

    @Test void imageOnlyConsultationIsPendingAndHasAnImagePreview() {
        var image=new MockMultipartFile("attachments","proof.png","image/png",new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1});
        UUID conversation=(UUID)support.send(null,null,UUID.randomUUID(),"",List.of(image),false,"guest-image").get("conversation_id");
        assertEquals("[图片]",support.detail(conversation,false,"guest-image").get("preview"));
        assertThrows(BusinessException.class,() -> support.detail(conversation,false,"another-guest"));
        UUID admin=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role) VALUES (?,?,?,'test','ADMIN')",admin,"admin-"+admin,admin+"@example.com");
        RequestContext.set(new RequestContext.UserInfo(admin,"admin","ADMIN"));
        assertEquals(true,support.detail(conversation,true,null).get("needs_attention"));
        assertEquals(1L,support.detail(conversation,true,null).get("unread"));
        assertTrue(isPending(conversation));
        support.send(conversation,null,UUID.randomUUID(),"",List.of(image),true,null);
        assertEquals(false,support.detail(conversation,true,null).get("needs_attention"));
        assertEquals("[图片]",support.detail(conversation,true,null).get("preview"));
        RequestContext.clear();
        support.send(conversation,null,UUID.randomUUID(),"文字优先",List.of(image),false,"guest-image");
        assertEquals("文字优先",support.detail(conversation,false,"guest-image").get("preview"));
        RequestContext.set(new RequestContext.UserInfo(admin,"admin","ADMIN"));
        assertTrue(isPending(conversation));
    }

    @Test void consultationAttentionRemainsIsolatedBySiteAndCustomer() {
        UUID owner=UUID.randomUUID(), first=UUID.randomUUID(), second=UUID.randomUUID();
        UUID site=UUID.randomUUID(), admin=UUID.randomUUID();
        for (UUID id:List.of(owner,first,second)) {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id) VALUES (?,?,?,'test','USER',?,(SELECT id FROM user_levels WHERE is_default=true))",
                    id,"user-"+id,id+"@example.com",Math.abs((long)id.hashCode())+10_000_000_000L);
        }
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role) VALUES (?,?,?,'test','ADMIN')",admin,"admin-"+admin,admin+"@example.com");
        jdbc.update("INSERT INTO subsites(id,owner_id,prefix,name,enabled) VALUES (?,?,?,'隔离分站',true)",site,owner,"r1-"+site.toString().substring(0,8));
        Subsite current=new Subsite(); current.setId(site); SiteRequestContext.set(current);
        RequestContext.set(new RequestContext.UserInfo(first,"first","USER"));
        UUID conversation=(UUID)support.send(null,null,UUID.randomUUID(),"分站咨询",List.of(),false,null).get("conversation_id");
        assertEquals(true,support.detail(conversation,false,null).get("needs_attention"));
        RequestContext.set(new RequestContext.UserInfo(second,"second","USER"));
        assertThrows(BusinessException.class,() -> support.detail(conversation,false,null));
        assertTrue(support.list(false,null,null,null,false,false,0,30).isEmpty());
        RequestContext.set(new RequestContext.UserInfo(first,"first","USER"));
        SiteRequestContext.clear();
        assertThrows(BusinessException.class,() -> support.detail(conversation,false,null));
        assertTrue(support.list(false,null,null,null,false,false,0,30).isEmpty());
        RequestContext.set(new RequestContext.UserInfo(admin,"admin","ADMIN"));
        assertTrue(support.list(true,null,site.toString(),null,false,true,0,30).stream().anyMatch(row -> conversation.equals(row.get("id"))));
        assertTrue(support.list(true,null,UUID.randomUUID().toString(),null,false,true,0,30).isEmpty());
    }

    @Test void guestOrderAndPrivateAttachmentRequireTheOriginalSession() {
        UUID order=UUID.randomUUID();
        jdbc.update("INSERT INTO orders(id,total_amount,actual_amount,status,order_type,session_token) VALUES (?,1,1,'PAID','NORMAL','guest-a')",order);
        assertThrows(BusinessException.class,() -> support.orderContext(order,"guest-b"));
        assertNotNull(support.orderContext(order,"guest-a").get("conversation_id"));
        var file=new MockMultipartFile("attachments","proof.png","image/png",new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1});
        UUID key=UUID.randomUUID();
        Map<String,Object> sent=support.send(null,order,key,"凭证",List.of(file),false,"guest-a");
        UUID conversation=(UUID)sent.get("conversation_id");
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM after_sale_requests WHERE order_id=?",Integer.class,order));
        assertEquals(sent,support.send(conversation,order,key,"凭证",List.of(file),false,"guest-a"));
        var changed=new MockMultipartFile("attachments","proof.png","image/png",new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,2});
        assertThrows(BusinessException.class,() -> support.send(conversation,order,key,"凭证",List.of(changed),false,"guest-a"));
        UUID attachment=jdbc.queryForObject("SELECT id FROM support_attachments",UUID.class);
        assertEquals("image/png",support.openAttachment(attachment,false,"guest-a").contentType());
        assertThrows(BusinessException.class,() -> support.openAttachment(attachment,false,"guest-b"));
        UUID user=UUID.randomUUID();
        RequestContext.set(new RequestContext.UserInfo(user,"other","USER"));
        assertThrows(BusinessException.class,() -> support.orderContext(order,"guest-a"));
        assertThrows(BusinessException.class,() -> support.detail(conversation,false,"guest-a"));
    }

    @Test void pendingSaleUsesOriginalReplyServiceAndClosedSaleAllowsOrdinaryConsultation() {
        UUID user=UUID.randomUUID(), admin=UUID.randomUUID(), order=UUID.randomUUID(), sale=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id) VALUES (?,?,?,'test','USER',?,(SELECT id FROM user_levels WHERE is_default=true))",user,"buyer-"+user,user+"@example.com",Math.abs(user.hashCode())+10000L);
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role) VALUES (?,?,?,'test','ADMIN')",admin,"admin-"+admin,admin+"@example.com");
        jdbc.update("INSERT INTO orders(id,user_id,total_amount,actual_amount,status,order_type) VALUES (?,?,5,5,'PAID','NORMAL')",order,user);
        RequestContext.set(new RequestContext.UserInfo(user,"buyer","USER"));
        UUID earlierKey=UUID.randomUUID();
        support.send(null,order,earlierKey,"之前的咨询",List.of(),false,null);
        jdbc.update("INSERT INTO after_sale_requests(id,order_id,user_id,status,source_order_status,reason,waiting_for,last_activity_at,submitted_at) VALUES (?,?,?,'PENDING','PAID','需要处理的问题','ADMIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sale,order,user);
        jdbc.update("UPDATE orders SET status='AFTER_SALE' WHERE id=?",order);
        UUID evidence=UUID.randomUUID();
        jdbc.update("INSERT INTO after_sale_attachments(id,request_id,storage_key,original_filename,content_type,file_size) VALUES (?,?,'after-sales/test/proof.png','proof.png','image/png',9)",evidence,sale);
        UUID conversation=jdbc.queryForObject("SELECT id FROM support_conversations WHERE order_id=?",UUID.class,order);
        RequestContext.set(new RequestContext.UserInfo(user,"buyer","USER"));
        var initial=items(support.activities(conversation,false,null,null,null,40));
        var application=initial.stream().filter(row -> "AFTER_SALE_APPLY".equals(row.get("source_type"))).findFirst().orElseThrow();
        assertEquals("需要处理的问题",application.get("content"));
        assertEquals(evidence,((Map<?,?>)((List<?>)application.get("attachments")).getFirst()).get("id"));
        UUID root=(UUID)support.orderContext(order,null).get("conversation_id");
        assertThrows(BusinessException.class,() -> support.replyAfterSale(root,earlierKey,"之前的咨询",order,sale,1,List.of(),false,null));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM after_sale_messages WHERE request_id=? AND client_key=?",Integer.class,sale,earlierKey));
        assertThrows(BusinessException.class,() -> support.replyAfterSale(root,earlierKey,"不同内容",order,sale,1,List.of(),false,null));
        support.send(root,order,UUID.randomUUID(),"普通订单咨询不受待处理售后阻止",List.of(),false,null);
        assertEquals("ADMIN",jdbc.queryForObject("SELECT waiting_for FROM after_sale_requests WHERE id=?",String.class,sale));
        UUID key=UUID.randomUUID();
        support.replyAfterSale(root,key,"补充说明",order,sale,1,List.of(),false,null);
        support.replyAfterSale(root,key,"补充说明",order,sale,1,List.of(),false,null);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM after_sale_messages WHERE request_id=? AND client_key=?",Integer.class,sale,key));
        assertEquals("ADMIN",jdbc.queryForObject("SELECT waiting_for FROM after_sale_requests WHERE id=?",String.class,sale));
        RequestContext.set(new RequestContext.UserInfo(admin,"admin","ADMIN"));
        var adminEvents=items(support.activities(conversation,true,null,null,null,40));
        support.markRead(conversation,true,null,Long.parseLong((String)adminEvents.getLast().get("cursor")));
        assertEquals(0L,support.detail(conversation,true,null).get("unread"));
        assertEquals("ADMIN",support.detail(conversation,true,null).get("waiting_for"));
        assertEquals(true,support.detail(conversation,true,null).get("needs_attention"));
        assertTrue(support.list(true,null,null,null,false,true,0,30).stream().anyMatch(row -> conversation.equals(row.get("id"))));
        support.replyAfterSale(root,UUID.randomUUID(),"请补充",order,sale,1,List.of(),true,null);
        assertEquals("CUSTOMER",support.detail(conversation,true,null).get("waiting_for"));
        assertEquals(false,support.detail(conversation,true,null).get("needs_attention"));
        assertFalse(isPending(conversation));
        RequestContext.set(new RequestContext.UserInfo(user,"buyer","USER"));
        support.replyAfterSale(root,UUID.randomUUID(),"补充材料",order,sale,1,List.of(),false,null);
        RequestContext.set(new RequestContext.UserInfo(admin,"admin","ADMIN"));
        assertEquals(true,support.detail(conversation,true,null).get("needs_attention"));
        assertTrue(isPending(conversation));
        afterSales.reject(order,"已处理");
        RequestContext.set(new RequestContext.UserInfo(user,"buyer","USER"));
        support.replyAfterSale(root,key,"补充说明",order,sale,1,List.of(),false,null);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM after_sale_messages WHERE request_id=? AND client_key=?",Integer.class,sale,key));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM support_messages WHERE conversation_id=? AND client_key=?",Integer.class,conversation,key));
        support.send(root,order,UUID.randomUUID(),"继续咨询",List.of(),false,null);
        assertEquals("REJECTED",jdbc.queryForObject("SELECT status FROM after_sale_requests WHERE id=?",String.class,sale));
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
        RequestContext.set(new RequestContext.UserInfo(admin,"admin","ADMIN"));
        assertNull(timeline.detail(root,true,null).get("waiting_for"));
        assertEquals(true,timeline.detail(root,true,null).get("needs_attention"));
        assertTrue(timeline.list(true,null,"main",null,false,true,null,null,0,30).stream().anyMatch(row -> root.equals(row.get("id"))));
        support.send(root,order,UUID.randomUUID(),"后续回复",List.of(),true,null);
        assertFalse(Boolean.TRUE.equals(timeline.detail(root,true,null).get("needs_attention")));
    }

    @Test void cursorDoesNotAdvancePastAnUncommittedEarlierMessage() throws Exception {
        UUID conversation=(UUID)support.send(null,null,UUID.randomUUID(),"初始",List.of(),false,"guest-c").get("conversation_id");
        UUID first=UUID.randomUUID(), second=UUID.randomUUID();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var one=dataSource.getConnection()) {
            one.setAutoCommit(false);
            try (var insert=one.prepareStatement("INSERT INTO support_messages(id,conversation_id,sender_type,content,client_key,request_fingerprint) VALUES (?,?,'CUSTOMER','first',?,decode(repeat('aa',32),'hex'))")) {
                insert.setObject(1,first); insert.setObject(2,conversation); insert.setObject(3,UUID.randomUUID()); insert.executeUpdate();
            }
            var started=new java.util.concurrent.CountDownLatch(1);
            var later=executor.submit(() -> {
                try (var two=dataSource.getConnection()) {
                    two.setAutoCommit(false);
                    try (var insert=two.prepareStatement("INSERT INTO support_messages(id,conversation_id,sender_type,content,client_key,request_fingerprint) VALUES (?,?,'CUSTOMER','second',?,decode(repeat('bb',32),'hex'))")) {
                        insert.setObject(1,second); insert.setObject(2,conversation); insert.setObject(3,UUID.randomUUID()); started.countDown(); insert.executeUpdate();
                    }
                    two.commit();
                }
                return null;
            });
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,() -> later.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM support_activity WHERE source_id IN (?,?)",Integer.class,first,second));
            one.commit(); later.get(5,java.util.concurrent.TimeUnit.SECONDS);
            var ids=jdbc.queryForList("SELECT source_id FROM support_activity WHERE source_id IN (?,?) ORDER BY seq",UUID.class,first,second);
            assertEquals(List.of(first,second),ids);
        } finally { executor.shutdownNow(); }
    }

    @Test void customerCursorSerializesAcrossTwoOrderThreadsRefundActivityAndGeneralChat() throws Exception {
        UUID buyer=UUID.randomUUID(), orderA=UUID.randomUUID(), orderB=UUID.randomUUID();
        long userNo=Math.abs((long)buyer.hashCode())+50000L;
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id) VALUES (?,?,?,'test','USER',?,(SELECT id FROM user_levels WHERE is_default=true))",
                buyer,"cursor-"+buyer,buyer+"@example.com",userNo);
        jdbc.update("INSERT INTO orders(id,user_id,total_amount,actual_amount,status,order_type) VALUES (?,?,1,1,'PAID','NORMAL'),(?,?,1,1,'PAID','NORMAL')",
                orderA,buyer,orderB,buyer);
        UUID orderThreadA=jdbc.queryForObject("SELECT support_order_conversation(?)",UUID.class,orderA);
        UUID orderThreadB=jdbc.queryForObject("SELECT support_order_conversation(?)",UUID.class,orderB);
        RequestContext.set(new RequestContext.UserInfo(buyer,"cursor-buyer","USER"));
        UUID root=(UUID)support.generalContext(null).get("conversation_id");
        RequestContext.clear();
        UUID early=UUID.randomUUID(), secondOrder=UUID.randomUUID(), refund=UUID.randomUUID();
        UUID messageKey=UUID.randomUUID();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(3);
        try (var first=dataSource.getConnection()) {
            first.setAutoCommit(false);
            try (var insert=first.prepareStatement("INSERT INTO support_activity(conversation_id,source_type,source_id,notify_customer,notify_admin) VALUES (?,'AFTER_SALE_APPLY',?,false,true)")) {
                insert.setObject(1,orderThreadA); insert.setObject(2,early); insert.executeUpdate();
            }
            var laterOrder=java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                started.countDown(); jdbc.update("INSERT INTO support_activity(conversation_id,source_type,source_id,notify_customer,notify_admin) VALUES (?,'AFTER_SALE_APPLY',?,false,true)",orderThreadB,secondOrder); return secondOrder;
            },executor);
            var laterRefund=java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                started.countDown(); jdbc.update("INSERT INTO support_activity(conversation_id,source_type,source_id,notify_customer,notify_admin) VALUES (?,'ORDER_REFUND',?,true,false)",orderThreadB,refund); return refund;
            },executor);
            var laterGeneral=java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                started.countDown();
                RequestContext.set(new RequestContext.UserInfo(buyer,"cursor-buyer","USER"));
                try { return support.send(root,null,messageKey,"普通咨询",List.of(),false,null).get("message_id"); }
                finally { RequestContext.clear(); }
            },executor);
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,() -> laterOrder.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,() -> laterRefund.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,() -> laterGeneral.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            first.commit();
            Object generalMessage=laterGeneral.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(secondOrder,laterOrder.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(refund,laterRefund.get(5,java.util.concurrent.TimeUnit.SECONDS));
            UUID general=(UUID)generalMessage;
            var ordered=jdbc.query("SELECT source_id,seq FROM support_activity WHERE source_id IN (?,?,?,?) ORDER BY seq",
                    (rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getLong(2)},early,secondOrder,refund,general);
            assertEquals(4,ordered.size());
            assertEquals(early,ordered.getFirst()[0]);
            for(int index=1;index<ordered.size();index++) assertTrue((Long)ordered.get(index)[1]>(Long)ordered.get(index-1)[1]);
        } finally { executor.shutdownNow(); }
    }

    @Test void guestEndpointsArePublicWhileTheWorkbenchRequiresAnAdministrator() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(webContext).addFilters(security).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/support/general")
                .contextPath("/api").header("X-Session-Token","guest-a").header("X-Message-Viewer","guest"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        UUID conversation=(UUID)support.send(null,null,UUID.randomUUID(),"hello",List.of(),false,"guest-a").get("conversation_id");
        long cursor=Long.parseLong((String)items(support.activities(conversation,false,"guest-a",null,null,1)).getFirst().get("cursor"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/support/conversations/{id}/read",conversation)
                .contextPath("/api").header("X-Session-Token","guest-a").header("X-Message-Viewer","guest")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"cursor\":\""+cursor+"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/support/conversations").contextPath("/api"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/support/conversations").contextPath("/api")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("buyer").roles("USER")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test void transferredBuyerKeepsAccessToTheDisabledSitesHistoricalOrder() {
        UUID owner=UUID.randomUUID(), buyer=UUID.randomUUID(), site=UUID.randomUUID(), order=UUID.randomUUID(), sale=UUID.randomUUID();
        long number=Math.abs(buyer.hashCode())+20000L;
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id) VALUES (?,?,?,'test','USER',?,(SELECT id FROM user_levels WHERE is_default=true)),(?,?,?,'test','USER',?,(SELECT id FROM user_levels WHERE is_default=true))",owner,"owner-"+owner,owner+"@example.com",number,buyer,"buyer-"+buyer,buyer+"@example.com",number+1);
        jdbc.update("INSERT INTO subsites(id,owner_id,prefix,name,enabled) VALUES (?,?,?,'旧分站',false)",site,owner,"test-"+site.toString().substring(0,8));
        jdbc.update("UPDATE users SET subsite_id=? WHERE id=?",site,buyer);
        jdbc.update("INSERT INTO orders(id,user_id,subsite_id,subsite_owner_id_snapshot,subsite_profit,total_amount,actual_amount,status,order_type) VALUES (?,?,?,?,0,1,1,'AFTER_SALE','NORMAL')",order,buyer,site,owner);
        jdbc.update("INSERT INTO after_sale_requests(id,order_id,user_id,status,source_order_status,reason,waiting_for,last_activity_at,submitted_at) VALUES (?,?,?,'PENDING','PAID','历史售后申请','ADMIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sale,order,buyer);
        jdbc.update("UPDATE users SET subsite_id=NULL WHERE id=?",buyer);
        UUID conversation=jdbc.queryForObject("SELECT id FROM support_conversations WHERE order_id=?",UUID.class,order);
        RequestContext.set(new RequestContext.UserInfo(buyer,"buyer","USER"));
        UUID currentRoot=(UUID)support.orderContext(order,null).get("conversation_id");
        assertNotEquals(conversation,currentRoot);
        assertEquals(order,timeline.detail(conversation,false,null).get("order_id"));
        assertTrue(support.list(false,null,null,null,false,false,0,30).stream().anyMatch(row -> conversation.equals(row.get("id"))));
        Subsite other=new Subsite(); other.setId(UUID.randomUUID()); SiteRequestContext.set(other);
        assertThrows(BusinessException.class,() -> support.detail(conversation,false,null));
    }

    @Test void oneCustomerTimelineAggregatesGeneralAndMultipleSaleThreadsWithPerMessageReceipts() {
        UUID buyer=UUID.randomUUID(), admin=UUID.randomUUID(), orderA=UUID.randomUUID(), orderB=UUID.randomUUID();
        long userNo=Math.abs((long)buyer.hashCode())+40000L;
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id) VALUES (?,?,?,'test','USER',?,(SELECT id FROM user_levels WHERE is_default=true))",
                buyer,"timeline-"+buyer,buyer+"@example.com",userNo);
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role) VALUES (?,?,?,'test','ADMIN')",admin,"timeline-admin-"+admin,admin+"@example.com");
        RequestContext.set(new RequestContext.UserInfo(buyer,"buyer","USER"));
        UUID root=(UUID)support.generalContext(null).get("conversation_id");
        support.send(root,null,UUID.randomUUID(),"普通咨询",List.of(),false,null);
        jdbc.update("INSERT INTO orders(id,user_id,total_amount,actual_amount,status,order_type) VALUES (?,?,8,8,'PAID','NORMAL'),(?,?,9,9,'PAID','NORMAL')",
                orderA,buyer,orderB,buyer);
        UUID requestA=UUID.randomUUID(), requestB=UUID.randomUUID();
        jdbc.update("INSERT INTO after_sale_requests(id,order_id,user_id,status,source_order_status,reason,waiting_for,last_activity_at,submitted_at) "
                + "VALUES (?,?,?,'PENDING','PAID','申请售后 A','ADMIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(?,?,?,'PENDING','PAID','申请售后 B','ADMIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                requestA,orderA,buyer,requestB,orderB,buyer);
        jdbc.update("UPDATE orders SET status='AFTER_SALE' WHERE id IN (?,?)",orderA,orderB);
        jdbc.update("INSERT INTO after_sale_messages(id,request_id,sender_type,sender_user_id,content) VALUES (? ,?,'ADMIN',?,'客服回复 A'),(?,?, 'ADMIN',?,'客服回复 B')",
                UUID.randomUUID(),requestA,admin,UUID.randomUUID(),requestB,admin);
        Map<String,Object> contextA=support.orderContext(orderA,requestA,1,null);
        Map<String,Object> contextB=support.orderContext(orderB,requestB,1,null);
        assertEquals(root,contextA.get("conversation_id")); assertEquals(root,contextB.get("conversation_id"));
        assertEquals(1,contextA.get("round")); assertEquals(1,contextB.get("round"));

        Map<String,Object> summary=timeline.detail(root,false,null);
        assertEquals(2L,summary.get("unread"));
        var firstPage=items(timeline.activities(root,false,null,null,null,40));
        var replies=firstPage.stream().filter(row -> "AFTER_SALE_MESSAGE".equals(row.get("source_type"))
                && "ADMIN".equals(row.get("sender_type"))).toList();
        assertEquals(2,replies.size());
        assertTrue(replies.stream().anyMatch(row -> orderA.equals(row.get("order_id")) && requestA.equals(row.get("request_id"))));
        assertTrue(replies.stream().anyMatch(row -> orderB.equals(row.get("order_id")) && requestB.equals(row.get("request_id"))));
        assertTrue(replies.stream().allMatch(row -> row.get("order_no") instanceof String));
        String cursorA=(String)replies.stream().filter(row -> orderA.equals(row.get("order_id"))).findFirst().orElseThrow().get("cursor");
        String cursorB=(String)replies.stream().filter(row -> orderB.equals(row.get("order_id"))).findFirst().orElseThrow().get("cursor");
        timeline.markRead(root,false,null,List.of(cursorA));
        assertEquals(1L,timeline.detail(root,false,null).get("unread"));
        String messageIdA=replies.stream().filter(row -> orderA.equals(row.get("order_id"))).findFirst().orElseThrow().get("id").toString().substring("AFTER_SALE_MESSAGE:".length());
        UUID oldA=jdbc.queryForObject("SELECT conversation_id FROM support_activity WHERE source_type='AFTER_SALE_MESSAGE' AND source_id=?",UUID.class,UUID.fromString(messageIdA));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM support_activity_receipts WHERE conversation_id=? AND viewer_type='CUSTOMER'",Integer.class,oldA));
        timeline.markRead(root,false,null,List.of(cursorB));
        assertEquals(0L,timeline.detail(root,false,null).get("unread"));

        RequestContext.set(new RequestContext.UserInfo(admin,"admin","ADMIN"));
        var adminRoot=timeline.list(true,null,"main","after_sale",false,true,null,null,0,30).stream()
                .filter(row -> root.equals(row.get("id"))).findFirst().orElseThrow();
        assertEquals(2,((List<?>)adminRoot.get("pending_after_sales")).size());
    }

    @Test void deliveredOrderCreatesOneCustomerResultAndAfterSaleRestoreDoesNotRepeatIt() {
        UUID buyer=UUID.randomUUID(), order=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id) VALUES (?,?,?,'test','USER',?,(SELECT id FROM user_levels WHERE is_default=true))",
                buyer,"delivery-result-"+buyer,buyer+"@example.com",Math.abs((long)buyer.hashCode())+60000L);
        jdbc.update("INSERT INTO orders(id,user_id,total_amount,actual_amount,status,order_type) VALUES (?,?,5,5,'PAID','NORMAL')",order,buyer);
        jdbc.update("UPDATE orders SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP WHERE id=?",order);
        UUID root=jdbc.queryForObject("SELECT id FROM support_conversations WHERE user_id=? AND order_id IS NULL",UUID.class,buyer);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM support_system_events WHERE source_type='ORDER' AND source_id=? AND event_type='ORDER_DELIVERED'",Integer.class,order));
        jdbc.update("UPDATE orders SET status='AFTER_SALE' WHERE id=?",order);
        jdbc.update("UPDATE orders SET status='DELIVERED' WHERE id=?",order);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM support_system_events WHERE source_type='ORDER' AND source_id=? AND event_type='ORDER_DELIVERED'",Integer.class,order));
        RequestContext.set(new RequestContext.UserInfo(buyer,"buyer","USER"));
        var result=items(timeline.activities(root,false,null,null,null,20)).stream()
                .filter(row -> "SYSTEM_RESULT".equals(row.get("source_type"))).findFirst().orElseThrow();
        assertEquals("ORDER_DELIVERED",result.get("event_type"));
        assertEquals(order,result.get("order_id"));
        assertTrue(result.get("order_no") instanceof String);
    }

    @Test void pagedHistoryAndIncrementalCatchUpUseStableNonoverlappingCursors() {
        UUID conversation=(UUID)support.send(null,null,UUID.randomUUID(),"message-0",List.of(),false,"guest-paging").get("conversation_id");
        for (int i=1;i<6;i++) support.send(conversation,null,UUID.randomUUID(),"message-"+i,List.of(),false,"guest-paging");
        var latest=support.activities(conversation,false,"guest-paging",null,null,2);
        var tail=items(latest);
        assertEquals(List.of("message-4","message-5"),tail.stream().map(row -> row.get("content")).toList());
        assertEquals(true,latest.get("has_more"));
        long before=Long.parseLong((String)latest.get("oldest_cursor"));
        var earlier=items(support.activities(conversation,false,"guest-paging",null,before,2));
        assertEquals(List.of("message-2","message-3"),earlier.stream().map(row -> row.get("content")).toList());
        long cursor=Long.parseLong((String)latest.get("newest_cursor"));
        for (int i=6;i<9;i++) support.send(conversation,null,UUID.randomUUID(),"message-"+i,List.of(),false,"guest-paging");
        var first=support.activities(conversation,false,"guest-paging",cursor,null,2);
        assertEquals(true,first.get("has_more"));
        var second=support.activities(conversation,false,"guest-paging",Long.parseLong((String)first.get("newest_cursor")),null,2);
        var caughtUp=new java.util.ArrayList<Map<String,Object>>(items(first)); caughtUp.addAll(items(second));
        assertEquals(List.of("message-6","message-7","message-8"),caughtUp.stream().map(row -> row.get("content")).toList());
        assertEquals(3,caughtUp.stream().map(row -> row.get("id")).distinct().count());
    }

    private boolean isPending(UUID conversation) {
        return support.list(true,null,null,null,false,true,0,30).stream().anyMatch(row -> conversation.equals(row.get("id")));
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> page) { return (List<Map<String,Object>>)page.get("items"); }
    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("myshop-support-test-"); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
}
