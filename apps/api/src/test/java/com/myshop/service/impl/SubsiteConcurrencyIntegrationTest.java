package com.myshop.service.impl;

import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.model.request.RegisterRequest;
import com.myshop.repository.SubsiteRepository;
import com.myshop.service.SubsiteManagementService;
import com.myshop.utils.CaptchaUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@EnabledIf(value = "postgresAvailable", disabledReason = "PostgreSQL test database or Docker required")
@SpringBootTest
class SubsiteConcurrencyIntegrationTest {
    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static boolean postgresAvailable() { return POSTGRES.available(); }
    @AfterAll static void close() { POSTGRES.close(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword); r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver"); r.add("spring.flyway.enabled",()->true);
        r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("app.public-site-url",()->"https://mc.mc520.top");
        r.add("jwt.secret",()->"subsite-concurrency-integration-secret-at-least-64-bytes-long-2026");
        r.add("bootstrap.admin.username",()->""); r.add("bootstrap.admin.email",()->""); r.add("bootstrap.admin.password",()->"");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired SubsiteManagementService service;
    @Autowired AuthServiceImpl auth;
    @Autowired com.myshop.service.OrderAccessService orderAccess;
    @Autowired com.myshop.utils.JwtUtils jwt;
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    @Autowired org.springframework.security.web.FilterChainProxy security;
    @Autowired com.myshop.config.SubsiteHostFilter hostFilter;
    @Autowired SubsiteRepository sites;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoBean CaptchaUtils captcha;
    final String tag = "sub" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    UUID level;
    final List<UUID> users = new ArrayList<>();
    @BeforeEach void configure() {
        level=jdbc.queryForObject("select id from user_levels where is_default = true",UUID.class);
        jdbc.update("update subsite_settings set self_service_enabled=true, opening_price=5.00, supply_level_id=? where id=1",level);
        jdbc.update("delete from subsite_allowed_levels"); jdbc.update("insert into subsite_allowed_levels(level_id) values (?)",level);
        when(captcha.verify(anyString(),anyString())).thenReturn(true);
    }
    @AfterEach void cleanup() {
        SiteRequestContext.clear();
        jdbc.update("delete from orders where subsite_id in (select id from subsites where prefix like ?)",tag+"%");
        jdbc.update("delete from subsite_opening_records where subsite_id in (select id from subsites where prefix like ?)",tag+"%");
        for (UUID id:users) jdbc.update("delete from balance_transactions where user_id=?",id);
        jdbc.update("update users set subsite_id=null where subsite_id in (select id from subsites where prefix like ?)",tag+"%");
        jdbc.update("delete from users where username=?",tag+"buyer");
        jdbc.update("delete from subsites where prefix like ?",tag+"%");
        for (UUID id:users) jdbc.update("delete from users where id=?",id);
        jdbc.update("delete from subsite_allowed_levels");
        jdbc.update("update subsite_settings set self_service_enabled=false, opening_price=0, supply_level_id=null where id=1");
    }
    UUID user(String suffix) {
        UUID id=UUID.randomUUID(); users.add(id); String name=tag+suffix;
        jdbc.update("insert into users(id,username,email,password_hash,role,user_no,user_level_id,balance,is_deleted) values (?,?,?,'test','USER',?,?,10.00,0)",
                id,name,name+"@example.com",Math.abs(id.getLeastSignificantBits() % 1000000000L)+1000000000L,level);
        return id;
    }
    @Test void duplicateRequestsChargeExactlyOnce() throws Exception {
        UUID owner=user("own"); String prefix=tag+"same";
        var request=new SubsiteManagementService.OpenRequest(null,prefix,"测试站");
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Map<String,Object>> a=pool.submit(()->{ start.await(); return service.open(owner,request,false); });
            Future<Map<String,Object>> b=pool.submit(()->{ start.await(); return service.open(owner,request,false); });
            start.countDown(); assertEquals(a.get(30,TimeUnit.SECONDS).get("id"),b.get(30,TimeUnit.SECONDS).get("id"));
        } finally { pool.shutdownNow(); }
        assertEquals(0,new BigDecimal("5.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,owner)));
        assertEquals(1,jdbc.queryForObject("select count(*) from subsite_opening_records where owner_id=?",Integer.class,owner));
        assertEquals(1,jdbc.queryForObject("select count(*) from balance_transactions where user_id=? and type='SUBSITE_OPENING'",Integer.class,owner));
    }
    @Test void competingOwnersCannotBothClaimPrefix() throws Exception {
        UUID first=user("one"), second=user("two"); String prefix=tag+"race";
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Boolean> a=pool.submit(()->attempt(start,first,prefix)); Future<Boolean> b=pool.submit(()->attempt(start,second,prefix));
            start.countDown(); assertNotEquals(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(1,jdbc.queryForObject("select count(*) from subsites where prefix=?",Integer.class,prefix));
        assertEquals(0,new BigDecimal("15.00").compareTo(jdbc.queryForObject("select sum(balance) from users where id in (?,?)",BigDecimal.class,first,second)));
    }
    boolean attempt(CountDownLatch start,UUID user,String prefix) throws Exception {
        start.await(); try { service.open(user,new SubsiteManagementService.OpenRequest(null,prefix,"测试站"),false); return true; }
        catch (com.myshop.exception.BusinessException ex) { return false; }
    }
    @Test void registrationWaitsForDisableAndCannotCommitAfterIt() throws Exception {
        UUID owner=user("own"); String prefix=tag+"off";
        UUID siteId=(UUID)service.open(owner,new SubsiteManagementService.OpenRequest(null,prefix,"测试站"),false).get("id");
        Subsite snapshot=sites.findById(siteId).orElseThrow();
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1), begun=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> disable=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->{
                service.status(siteId,false); locked.countDown();
                try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
            }));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            Future<Boolean> register=pool.submit(()->{
                SiteRequestContext.set(snapshot); begun.countDown();
                try { RegisterRequest r=new RegisterRequest(); r.setUsername(tag+"buyer"); r.setEmail(tag+"buyer@example.com");
                    r.setPhone("13800138000"); r.setPassword("testing123"); r.setCaptcha("ok"); r.setCaptchaId("ok");
                    auth.register(r,"127.0.0.1"); return true;
                } catch (com.myshop.exception.BusinessException ex) { return false; }
                finally { SiteRequestContext.clear(); }
            });
            assertTrue(begun.await(10,TimeUnit.SECONDS)); release.countDown(); disable.get(30,TimeUnit.SECONDS);
            assertFalse(register.get(30,TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
        assertEquals(0,jdbc.queryForObject("select count(*) from users where username=?",Integer.class,tag+"buyer"));
    }
    @Test void disabledSiteAndTransferredCustomerRetainHistory() {
        UUID owner=user("own"), buyer=user("client");
        UUID site=(UUID)service.open(owner,new SubsiteManagementService.OpenRequest(null,tag+"history","测试站"),false).get("id");
        jdbc.update("update users set subsite_id=? where id=?",site,buyer);
        UUID order=UUID.randomUUID();
        jdbc.update("""
            insert into orders(id,user_id,total_amount,actual_amount,status,order_type,payment_method,payment_channel_code,paid_at,subsite_id,subsite_owner_id_snapshot,subsite_profit)
            values (?,?,15,15,'PAID','NORMAL','ONLINE','test',current_timestamp,?,?,5)
            """,order,buyer,site,owner);
        service.status(site,false);
        SiteRequestContext.set(sites.findById(site).orElseThrow());
        assertNotNull(orderAccess.getOrder(order,buyer,null));
        SiteRequestContext.clear();
        service.transfer(site,new SubsiteManagementService.TransferRequest(buyer,level));
        assertNotNull(orderAccess.getOrder(order,buyer,null));
        assertNull(jdbc.queryForObject("select subsite_id from users where id=?",UUID.class,buyer));
        assertEquals(site,jdbc.queryForObject("select subsite_id from orders where id=?",UUID.class,order));
        assertEquals(0,new BigDecimal("5.00").compareTo(jdbc.queryForObject("select subsite_profit from orders where id=?",BigDecimal.class,order)));
    }
    @Test void realApiSerializesPageAndMoneyForStorefront() throws Exception {
        UUID owner=user("own"), buyer=user("client");
        UUID site=(UUID)service.open(owner,new SubsiteManagementService.OpenRequest(null,tag+"api","测试站"),false).get("id");
        jdbc.update("update users set subsite_id=? where id=?",site,buyer);
        UUID order=UUID.randomUUID();
        jdbc.update("""
            insert into orders(id,user_id,total_amount,actual_amount,status,order_type,payment_method,payment_channel_code,paid_at,subsite_id,subsite_owner_id_snapshot,subsite_profit)
            values (?,?,15,15,'PAID','NORMAL','ONLINE','test',current_timestamp,?,?,5)
            """,order,buyer,site,owner);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(hostFilter,security).build();
        var cookie = new jakarta.servlet.http.Cookie("myshop_access_token",jwt.generateToken(owner,tag+"own","USER",0));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String path : List.of("customers", "orders")) {
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/subsite/"+path)
                    .contextPath("/api").header("Host","mc.mc520.top").cookie(cookie))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn().getResponse();
            var page = mapper.readTree(response.getContentAsString()).path("data");
            assertTrue(page.path("content").isArray());
            assertTrue(page.path("total_pages").isIntegralNumber(),page.toString());
            if (path.equals("orders")) {
                assertTrue(page.path("content").get(0).path("actual_amount").isNumber());
                assertTrue(page.path("content").get(0).path("subsite_profit").isNumber());
            }
        }
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/subsite/overview")
                .contextPath("/api").header("Host","mc.mc520.top").cookie(cookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse();
        var data=mapper.readTree(response.getContentAsString()).path("data");
        assertTrue(data.path("estimated_profit").isNumber(),data.toString());
        assertEquals(0,new BigDecimal("5.00").compareTo(data.path("estimated_profit").decimalValue()));
    }
}
