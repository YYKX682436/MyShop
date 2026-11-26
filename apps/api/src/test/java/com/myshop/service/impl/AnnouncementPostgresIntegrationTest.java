package com.myshop.service.impl;

import com.myshop.context.RequestContext;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.exception.BusinessException;
import com.myshop.repository.SubsiteRepository;
import com.myshop.service.AnnouncementService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIf(value = "postgresAvailable", disabledReason = "Disposable PostgreSQL required")
class AnnouncementPostgresIntegrationTest {
    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static boolean postgresAvailable() { return POSTGRES.available(); }
    @AfterAll static void close() { POSTGRES.close(); }
    @AfterEach void clearContext() { RequestContext.clear(); SiteRequestContext.clear(); }

    JdbcTemplate jdbc;
    SubsiteRepository sites;
    AnnouncementService service;

    @BeforeEach void prepare() throws Exception {
        var data = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(data).cleanDisabled(false).load();
        flyway.clean();
        Flyway.configure().dataSource(data).target("63").load().migrate();
        jdbc = new JdbcTemplate(data);
        var root = java.nio.file.Files.createTempDirectory("myshop-announcement-media-");
        try {
            var storage = new com.myshop.service.UploadStorage(root.toString()); storage.initialize();
            var paths = new com.myshop.service.ManagedMediaReferences(jdbc, "/api/uploads", "http://localhost:3000");
            var indexer = new com.myshop.service.MediaLegacyIndexer(jdbc, storage, paths);
            indexer.indexBeforeCutover(); flyway.migrate(); indexer.clearOldDeletedReferences();
        } finally {
            try (var files = java.nio.file.Files.walk(root)) {
                for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
            }
        }
        sites = mock(SubsiteRepository.class);
        service = new AnnouncementService(jdbc, sites);
    }

    @Test void readsArePerIdentityAndEditingDoesNotRepublishOrResetUnread() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id,balance) VALUES (?, 'notice-user','notice@example.com','test','USER',123459,(SELECT id FROM user_levels LIMIT 1),0)", user);
        UUID id = service.create(null, new AnnouncementService.Edit("标题", "正文"));
        service.publish(null, id);
        RequestContext.set(new RequestContext.UserInfo(user, "notice-user", "USER"));
        assertTrue(unread(service.publicList(null, 0, 30, "guest-one"), id));
        assertEquals(1L,service.summary(null,"guest-one").get("unread"));
        service.markRead(null, id, 1, "guest-one");
        var publishedAt = jdbc.queryForObject("SELECT published_at FROM site_announcements WHERE id=?", java.time.LocalDateTime.class, id);
        service.edit(null, id, new AnnouncementService.Edit("更新标题", "编辑正文"));
        assertFalse(unread(service.publicList(null, 0, 30, "guest-one"), id));
        assertEquals(0L,service.summary(null,"guest-one").get("unread"));
        service.publish(null, id);
        assertFalse(unread(service.publicList(null, 0, 30, "guest-one"), id));
        assertEquals(1, jdbc.queryForObject("SELECT publish_version FROM site_announcements WHERE id=?", Integer.class, id));
        assertEquals(publishedAt, jdbc.queryForObject("SELECT published_at FROM site_announcements WHERE id=?", java.time.LocalDateTime.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT read_version FROM site_announcement_reads WHERE announcement_id=? AND user_id=?", Integer.class, id, user));
        assertThrows(BusinessException.class, () -> service.markRead(null, id, 2, null));
        RequestContext.clear();
        assertTrue(unread(service.publicList(null, 0, 30, "guest-one"), id));
        service.markRead(null, id, 1, "guest-one");
        assertFalse(unread(service.publicList(null, 0, 30, "guest-one"), id));
        assertTrue(unread(service.publicList(null, 0, 30, "guest-two"), id));
        assertThrows(BusinessException.class, () -> service.markRead(null, id, 2, null));
    }

    @Test void notificationContentAndManagementRemainIsolatedAcrossSites() {
        UUID owner = UUID.randomUUID(), siteId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id,balance) VALUES (?, 'site-owner','site-owner@example.com','test','USER',123460,(SELECT id FROM user_levels LIMIT 1),0)", owner);
        jdbc.update("INSERT INTO subsites(id,owner_id,prefix,name) VALUES (?,?,'notice-site','通知分站')", siteId, owner);
        UUID main = service.create(null, new AnnouncementService.Edit("主站", "主站正文"));
        UUID sub = service.create(siteId, new AnnouncementService.Edit("分站", "分站正文"));
        service.publish(null, main); service.publish(siteId, sub);
        assertEquals(sub, service.publicDetail(siteId, sub).get("id"));
        assertThrows(BusinessException.class, () -> service.publicDetail(null, sub));
        service.withdraw(siteId, sub);
        assertThrows(BusinessException.class, () -> service.publicDetail(siteId, sub));
        assertEquals(main, service.publicDetail(null, main).get("id"));

        Subsite site = new Subsite(); site.setId(siteId); site.setOwnerId(owner);
        when(sites.findByOwnerId(owner)).thenReturn(Optional.of(site));
        RequestContext.set(new RequestContext.UserInfo(owner, "site-owner", "USER"));
        assertEquals(siteId, service.managementSite(false));
        assertThrows(BusinessException.class, () -> service.managementSite(true));
    }

    @Test void levelAudienceIsAnExactPrivateScopeAndReadAllOnlyTouchesVisibleRows() {
        UUID levelA = addLevel("通知等级 A", 1, 101);
        UUID levelB = addLevel("通知等级 B", 2, 102);
        UUID levelC = addLevel("通知等级 C", 3, 103);
        UUID userA = addUser("notice-level-a", "notice-level-a@example.com", levelA, 123470);
        UUID userB = addUser("notice-level-b", "notice-level-b@example.com", levelB, 123471);
        UUID userC = addUser("notice-level-c", "notice-level-c@example.com", levelC, 123472);
        UUID owner = addUser("notice-site-owner", "notice-site-owner@example.com", levelA, 123473);
        UUID site = UUID.randomUUID();
        jdbc.update("INSERT INTO subsites(id,owner_id,prefix,name) VALUES (?,?,'audience-site','通知站')", site, owner);
        jdbc.update("INSERT INTO subsite_allowed_levels(level_id) VALUES (?), (?)", levelA, levelB);
        assertThrows(BusinessException.class, () -> service.save(site, null, new AnnouncementService.Edit(UUID.randomUUID(),
                null, "未允许等级", "LEVELS", List.of(levelC), true)));

        UUID restricted = service.save(site, null, new AnnouncementService.Edit(UUID.randomUUID(), null,
                "等级范围正文", "LEVELS", List.of(levelA, levelB), true));
        assertEquals(2, service.managementData(site).get("levels") instanceof List<?> rows ? rows.size() : 0);

        RequestContext.set(new RequestContext.UserInfo(userA, "notice-level-a", "USER"));
        assertTrue(unread(service.publicList(site, 0, 30, "guest-a"), restricted));
        assertEquals(restricted, service.publicDetail(site, restricted, "guest-a").get("id"));
        assertEquals(true, service.publicDetail(site, restricted, "guest-a").get("unread"));
        List<Map<String, Object>> receipts = service.markAllVisibleRead(site, "guest-a");
        assertEquals(restricted, receipts.getFirst().get("id"));
        assertEquals(false, service.publicDetail(site, restricted, "guest-a").get("unread"));
        assertEquals(0L, service.summary(site, "guest-a").get("unread"));

        RequestContext.set(new RequestContext.UserInfo(userB, "notice-level-b", "USER"));
        assertTrue(unread(service.publicList(site, 0, 30, "guest-b"), restricted));
        assertEquals(1L, service.summary(site, "guest-b").get("unread"));
        assertEquals(restricted, service.publicDetail(site, restricted, "guest-b").get("id"));
        assertEquals(true, service.publicDetail(site, restricted, "guest-b").get("unread"));
        service.markRead(site, restricted, 1, "guest-b");
        service.markAllVisibleRead(site, "guest-b");
        assertEquals(false, service.publicDetail(site, restricted, "guest-b").get("unread"));
        assertEquals(0L, service.summary(site, "guest-b").get("unread"));

        RequestContext.set(new RequestContext.UserInfo(userC, "notice-level-c", "USER"));
        assertFalse(service.publicList(site, 0, 30, "guest-c").stream().anyMatch(row -> restricted.equals(row.get("id"))));
        assertEquals(0L, service.summary(site, "guest-c").get("unread"));
        assertThrows(BusinessException.class, () -> service.publicDetail(site, restricted, "guest-c"));

        jdbc.update("UPDATE user_levels SET enabled=false WHERE id=?", levelA);
        assertFalse(service.publicList(site, 0, 30, "guest-a").stream().anyMatch(row -> restricted.equals(row.get("id"))));
        assertThrows(BusinessException.class, () -> service.save(site, null, new AnnouncementService.Edit(UUID.randomUUID(),
                "停用等级", "正文", "LEVELS", List.of(levelA), true)));
        assertThrows(BusinessException.class, () -> service.save(site, null, new AnnouncementService.Edit(UUID.randomUUID(),
                "公开上传文件", "![图片](/api/uploads/private-looking.png)", "LEVELS", List.of(levelB), true)));
    }

    @Test void createRetryIsIdempotentAndCannotReuseAKeyForDifferentContent() {
        UUID key = UUID.randomUUID();
        var edit = new AnnouncementService.Edit(key, "标题", "同一正文", "ALL", List.of(), true);
        UUID first = service.save(null, null, edit);
        assertEquals(first, service.save(null, null, edit));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM site_announcements WHERE create_key=?", Integer.class, key));
        assertThrows(BusinessException.class, () -> service.save(null, null,
                new AnnouncementService.Edit(key, "不同标题", "不同正文", "ALL", List.of(), true)));
    }

    @Test void refreshRechecksLoadedBodyPublicationAndCurrentAudience() {
        UUID levelA = addLevel("刷新等级 A", 1, 211);
        UUID levelB = addLevel("刷新等级 B", 2, 212);
        UUID user = addUser("notice-refresh", "notice-refresh@example.com", levelA, 123480);
        UUID id = service.save(null, null, new AnnouncementService.Edit(UUID.randomUUID(), "标题",
                "旧正文", "LEVELS", List.of(levelA), true));
        RequestContext.set(new RequestContext.UserInfo(user, "notice-refresh", "USER"));
        assertEquals("旧正文", service.refreshVisible(null, List.of(id), null).getFirst().get("body"));

        service.edit(null, id, new AnnouncementService.Edit(UUID.randomUUID(), "标题已编辑",
                "新正文", "LEVELS", List.of(levelA), false));
        assertEquals("新正文", service.refreshVisible(null, List.of(id), null).getFirst().get("body"));

        jdbc.update("UPDATE users SET user_level_id=? WHERE id=?", levelB, user);
        assertTrue(service.refreshVisible(null, List.of(id), null).isEmpty());
        assertThrows(BusinessException.class, () -> service.publicDetail(null, id, null));

        jdbc.update("UPDATE users SET user_level_id=? WHERE id=?", levelA, user);
        assertFalse(service.refreshVisible(null, List.of(id), null).isEmpty());
        service.withdraw(null, id);
        assertTrue(service.refreshVisible(null, List.of(id), null).isEmpty());
    }

    @Test void firstPageReportsTheOldestCurrentlyVisibleUnreadNoticeOutsideLatestPage() {
        UUID userLevel=addLevel("未读锚点等级",1,221);
        UUID user=addUser("notice-anchor", "notice-anchor@example.com", userLevel, 123490);
        UUID oldestUnread=null;
        for (int index=0;index<31;index++) {
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO site_announcements(id,title,body,status,publish_version,published_at) VALUES (?,?,'正文','PUBLISHED',1,CURRENT_TIMESTAMP-(? * INTERVAL '1 minute'))",
                    id,"通知 "+index,31-index);
            if (index==0) oldestUnread=id;
            else jdbc.update("INSERT INTO site_announcement_reads(announcement_id,user_id,read_version) VALUES (?,?,1)",id,user);
        }
        RequestContext.set(new RequestContext.UserInfo(user,"notice-anchor","USER"));
        UUID unreadId=oldestUnread;
        Map<String,Object> page=service.publicList(null,null,30,null,true);
        @SuppressWarnings("unchecked") List<Map<String,Object>> visible=(List<Map<String,Object>>)page.get("items");
        assertEquals(30,visible.size());
        assertFalse(visible.stream().anyMatch(item -> unreadId.equals(item.get("id"))));
        assertEquals(unreadId,page.get("first_unread_id"));
        assertEquals(unreadId,service.publicDetail(null,unreadId,null).get("id"));
    }

    @Test void importedUnknownPublicationTimeDoesNotCreateHistoricalUnread() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO site_announcements(id,title,body,status,publish_version,published_at) VALUES (?,'旧公告','旧正文','PUBLISHED',1,NULL)", id);
        assertFalse(unread(service.publicList(null, 0, 30, "guest"), id));
        service.publish(null, id);
        assertFalse(unread(service.publicList(null, 0, 30, "guest"), id));
        assertEquals(1, jdbc.queryForObject("SELECT publish_version FROM site_announcements WHERE id=?", Integer.class, id));
        assertNull(jdbc.queryForObject("SELECT published_at FROM site_announcements WHERE id=?", java.time.LocalDateTime.class, id));
    }

    private boolean unread(List<Map<String, Object>> rows, UUID id) {
        return (Boolean) rows.stream().filter(row -> id.equals(row.get("id"))).findFirst().orElseThrow().get("unread");
    }

    private UUID addLevel(String name, int sort, int rank) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_levels(id,name,pricing_type,is_default,enabled,sort_order,upgrade_rank) VALUES (?,?,'RETAIL',false,true,?,?)",
                id, name, sort, rank);
        return id;
    }

    private UUID addUser(String name, String email, UUID level, int number) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id,balance) VALUES (?,?,?,'test','USER',?,?,0)",
                id, name, email, number, level);
        return id;
    }
}
