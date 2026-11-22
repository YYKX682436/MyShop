package com.myshop.service.impl;

import com.myshop.context.RequestContext;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.exception.BusinessException;
import com.myshop.service.SupportService;
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
    @Autowired AfterSaleService afterSales;
    @Autowired WebApplicationContext webContext;
    @Autowired FilterChainProxy security;

    @BeforeEach void reset() {
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
        var events=items(support.activities(conversation,true,null,null,null,40));
        long first=Long.parseLong((String)events.getFirst().get("cursor"));
        support.markRead(conversation,true,null,first);
        assertEquals(0L,support.detail(conversation,true,null).get("unread"));
        support.send(conversation,null,UUID.randomUUID(),"客服回复",List.of(),true,null);
        RequestContext.clear();
        assertEquals(1L,support.detail(conversation,false,"guest-a").get("unread"));
        assertEquals(1L,support.customerSummary("guest-a").get("unread"));
        var next=items(support.activities(conversation,false,"guest-a",first,null,40));
        assertEquals(1,next.size());
        long second=Long.parseLong((String)next.getFirst().get("cursor"));
        support.markRead(conversation,false,"guest-a",second);
        support.markRead(conversation,false,"guest-a",first);
        assertEquals(0L,support.detail(conversation,false,"guest-a").get("unread"));
        assertEquals(second,jdbc.queryForObject("SELECT read_seq FROM support_read_states WHERE conversation_id=? AND viewer_type='CUSTOMER'",Long.class,conversation));
        Subsite other=new Subsite(); other.setId(UUID.randomUUID()); SiteRequestContext.set(other);
        assertThrows(BusinessException.class,() -> support.detail(conversation,false,"guest-a"));
    }

    @Test void guestOrderAndPrivateAttachmentRequireTheOriginalSession() {
        UUID order=UUID.randomUUID();
        jdbc.update("INSERT INTO orders(id,total_amount,actual_amount,status,order_type,session_token) VALUES (?,1,1,'PAID','NORMAL','guest-a')",order);
        assertThrows(BusinessException.class,() -> support.orderContext(order,"guest-b"));
        assertNull(support.orderContext(order,"guest-a").get("conversation_id"));
        var file=new MockMultipartFile("attachments","proof.png","image/png",new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1});
        UUID key=UUID.randomUUID();
        Map<String,Object> sent=support.send(null,order,key,"凭证",List.of(file),false,"guest-a");
        UUID conversation=(UUID)sent.get("conversation_id");
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM after_sale_requests WHERE order_id=?",Integer.class,order));
        assertEquals(sent,support.send(conversation,null,key,"凭证",List.of(file),false,"guest-a"));
        var changed=new MockMultipartFile("attachments","proof.png","image/png",new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,2});
        assertThrows(BusinessException.class,() -> support.send(conversation,null,key,"凭证",List.of(changed),false,"guest-a"));
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
        support.replyAfterSale(conversation,earlierKey,"之前的咨询",List.of(),false,null);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM after_sale_messages WHERE request_id=? AND client_key=?",Integer.class,sale,earlierKey));
        assertThrows(BusinessException.class,() -> support.replyAfterSale(conversation,earlierKey,"不同内容",List.of(),false,null));
        assertThrows(BusinessException.class,() -> support.send(conversation,null,UUID.randomUUID(),"不应作为普通咨询",List.of(),false,null));
        UUID key=UUID.randomUUID();
        support.replyAfterSale(conversation,key,"补充说明",List.of(),false,null);
        support.replyAfterSale(conversation,key,"补充说明",List.of(),false,null);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM after_sale_messages WHERE request_id=? AND client_key=?",Integer.class,sale,key));
        assertEquals("ADMIN",jdbc.queryForObject("SELECT waiting_for FROM after_sale_requests WHERE id=?",String.class,sale));
        RequestContext.set(new RequestContext.UserInfo(admin,"admin","ADMIN"));
        var adminEvents=items(support.activities(conversation,true,null,null,null,40));
        support.markRead(conversation,true,null,Long.parseLong((String)adminEvents.getLast().get("cursor")));
        assertEquals(0L,support.detail(conversation,true,null).get("unread"));
        assertEquals("ADMIN",support.detail(conversation,true,null).get("waiting_for"));
        assertTrue(support.list(true,null,null,null,false,true,0,30).stream().anyMatch(row -> conversation.equals(row.get("id"))));
        afterSales.reject(order,"已处理");
        RequestContext.set(new RequestContext.UserInfo(user,"buyer","USER"));
        support.send(conversation,null,key,"补充说明",List.of(),false,null);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM after_sale_messages WHERE request_id=? AND client_key=?",Integer.class,sale,key));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM support_messages WHERE conversation_id=? AND client_key=?",Integer.class,conversation,key));
        support.send(conversation,null,UUID.randomUUID(),"继续咨询",List.of(),false,null);
        assertEquals("REJECTED",jdbc.queryForObject("SELECT status FROM after_sale_requests WHERE id=?",String.class,sale));
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
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
        assertEquals(conversation,support.orderContext(order,null).get("conversation_id"));
        assertEquals(conversation,support.detail(conversation,false,null).get("id"));
        assertTrue(support.list(false,null,null,null,false,false,0,30).stream().anyMatch(row -> conversation.equals(row.get("id"))));
        Subsite other=new Subsite(); other.setId(UUID.randomUUID()); SiteRequestContext.set(other);
        assertThrows(BusinessException.class,() -> support.detail(conversation,false,null));
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

    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> page) { return (List<Map<String,Object>>)page.get("items"); }
    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("myshop-support-test-"); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
}
