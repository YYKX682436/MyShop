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

    @BeforeEach void prepare() {
        var data = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(data).cleanDisabled(false).load();
        flyway.clean(); flyway.migrate();
        jdbc = new JdbcTemplate(data);
        sites = mock(SubsiteRepository.class);
        service = new AnnouncementService(jdbc, sites);
    }

    @Test void readsArePerIdentityAndAdvanceOnlyForExplicitPublicationVersions() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id,balance) VALUES (?, 'notice-user','notice@example.com','test','USER',123459,(SELECT id FROM user_levels LIMIT 1),0)", user);
        UUID id = service.create(null, new AnnouncementService.Edit("标题", "正文"));
        service.publish(null, id);
        RequestContext.set(new RequestContext.UserInfo(user, "notice-user", "USER"));
        assertTrue(unread(service.publicList(null, 0, 30, "guest-one"), id));
        assertEquals(1L,service.summary(null,"guest-one").get("unread"));
        service.markRead(null, id, 1, "guest-one");
        service.edit(null, id, new AnnouncementService.Edit("更新标题", "编辑正文"));
        assertFalse(unread(service.publicList(null, 0, 30, "guest-one"), id));
        assertEquals(0L,service.summary(null,"guest-one").get("unread"));
        service.publish(null, id);
        assertTrue(unread(service.publicList(null, 0, 30, "guest-one"), id));
        service.markRead(null, id, 2, "guest-one");
        service.markRead(null, id, 1, "guest-one");
        assertFalse(unread(service.publicList(null, 0, 30, "guest-one"), id));
        assertEquals(2, jdbc.queryForObject("SELECT read_version FROM site_announcement_reads WHERE announcement_id=? AND user_id=?", Integer.class, id, user));
        assertThrows(BusinessException.class, () -> service.markRead(null, id, 3, null));
        RequestContext.clear();
        assertTrue(unread(service.publicList(null, 0, 30, "guest-one"), id));
        service.markRead(null, id, 2, "guest-one");
        assertFalse(unread(service.publicList(null, 0, 30, "guest-one"), id));
        assertTrue(unread(service.publicList(null, 0, 30, "guest-two"), id));
        assertThrows(BusinessException.class, () -> service.markRead(null, id, 2, null));
    }

    @Test void channelSelectionAndWithdrawalDoNotLeakAcrossSites() {
        UUID owner = UUID.randomUUID(), siteId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id,balance) VALUES (?, 'site-owner','site-owner@example.com','test','USER',123460,(SELECT id FROM user_levels LIMIT 1),0)", owner);
        jdbc.update("INSERT INTO subsites(id,owner_id,prefix,name) VALUES (?,?,'notice-site','通知分站')", siteId, owner);
        UUID main = service.create(null, new AnnouncementService.Edit("主站", "主站正文"));
        UUID sub = service.create(siteId, new AnnouncementService.Edit("分站", "分站正文"));
        service.publish(null, main); service.publish(siteId, sub);
        assertThrows(BusinessException.class, () -> service.setChannel(siteId, "BANNER", new AnnouncementService.Channel(main, true)));
        service.setChannel(siteId, "BANNER", new AnnouncementService.Channel(sub, true));
        assertEquals("分站正文", service.channels(siteId).get("announcement"));
        service.setChannel(siteId, "BANNER", new AnnouncementService.Channel(sub, false));
        assertEquals(false, service.channels(siteId).get("announcement_enabled"));
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

    @Test void importedUnknownPublicationTimeDoesNotCreateHistoricalUnread() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO site_announcements(id,title,body,status,publish_version,published_at) VALUES (?,'旧公告','旧正文','PUBLISHED',1,NULL)", id);
        assertFalse(unread(service.publicList(null, 0, 30, "guest"), id));
        service.publish(null, id);
        assertTrue(unread(service.publicList(null, 0, 30, "guest"), id));
    }

    private boolean unread(List<Map<String, Object>> rows, UUID id) {
        return (Boolean) rows.stream().filter(row -> id.equals(row.get("id"))).findFirst().orElseThrow().get("unread");
    }
}
