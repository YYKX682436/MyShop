package com.myshop.service;

import com.myshop.entity.*;
import com.myshop.exception.BusinessException;
import com.myshop.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubsiteManagementServiceTest {
    @Test void faviconUploadAndUpdatesAreRestrictedToTheOwner() {
        var sites=mock(SubsiteRepository.class); var images=mock(ImageUploadService.class);
        var jdbc=mock(JdbcTemplate.class);
        var service=new SubsiteManagementService(sites,mock(SubsiteSettingsRepository.class),mock(UserRepository.class),
                mock(UserLevelRepository.class),jdbc,mock(BalanceTransactionRepository.class),images);
        ReflectionTestUtils.setField(service,"publicSiteUrl","https://mc.mc520.top");
        UUID owner=UUID.randomUUID(), stranger=UUID.randomUUID();
        Subsite site=new Subsite(); site.setId(UUID.randomUUID()); site.setOwnerId(owner); site.setPrefix("owner"); site.setName("原站");
        when(sites.findByOwnerId(owner)).thenReturn(Optional.of(site));
        when(sites.findByIdForUpdate(site.getId())).thenReturn(Optional.of(site));
        when(sites.findByOwnerIdForUpdate(owner)).thenReturn(Optional.of(site));
        when(sites.existsByOwnerId(owner)).thenReturn(true);
        when(sites.findByOwnerId(stranger)).thenReturn(Optional.empty());
        var file=new org.springframework.mock.web.MockMultipartFile("file","icon.png","image/png",new byte[]{1});
        assertThrows(BusinessException.class, () -> service.uploadFavicon(stranger,file));
        verify(images,never()).uploadSiteFavicon(any());
        assertThrows(BusinessException.class, () -> service.updateMine(owner,new SubsiteManagementService.SiteUpdate("原站","https://other.example/icon.png")));
        when(images.uploadSiteFavicon(file)).thenReturn(new ImageUploadService.UploadedImage("/api/uploads/icon.png","icon.png",1,1,"test"));
        when(sites.save(site)).thenReturn(site);
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(site.getId()))).thenReturn(0L);
        assertEquals("/api/uploads/icon.png",service.uploadFavicon(owner,file).get("favicon_url"));
        assertEquals("/api/uploads/icon.png",site.getFaviconUrl());
        service.updateMine(owner,new SubsiteManagementService.SiteUpdate("新站",null));
        assertNull(site.getFaviconUrl());
        assertEquals("新站",site.getName());
    }
    @Test void insufficientBalanceDoesNotCreateOrDebit() {
        var sites=mock(SubsiteRepository.class); var settings=mock(SubsiteSettingsRepository.class);
        var users=mock(UserRepository.class); var levels=mock(UserLevelRepository.class);
        var jdbc=mock(JdbcTemplate.class); var ledger=mock(BalanceTransactionRepository.class);
        var service=new SubsiteManagementService(sites,settings,users,levels,jdbc,ledger,mock(ImageUploadService.class));
        ReflectionTestUtils.setField(service,"publicSiteUrl","https://mc.mc520.top");
        ReflectionTestUtils.setField(service,"reservedPrefixes", "");
        UUID owner=UUID.randomUUID(), levelId=UUID.randomUUID();
        User user=new User(); user.setId(owner); user.setUserLevelId(levelId); user.setBalance(new BigDecimal("1.00"));
        UserLevel level=new UserLevel(); level.setId(levelId);
        SubsiteSettings config=new SubsiteSettings(); config.setSupplyLevelId(levelId);
        config.setSelfServiceEnabled(true); config.setOpeningPrice(new BigDecimal("5.00"));
        when(settings.lockSettings()).thenReturn(Optional.of(config)); when(users.findByIdForUpdate(owner)).thenReturn(Optional.of(user));
        when(levels.findById(levelId)).thenReturn(Optional.of(level));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(levelId))).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.open(owner,new SubsiteManagementService.OpenRequest(null,"example","站点"),false));
        assertEquals(new BigDecimal("1.00"),user.getBalance()); verify(sites,never()).saveAndFlush(any());
        verify(ledger,never()).saveAndFlush(any());
        assertThrows(BusinessException.class, () -> service.open(owner,new SubsiteManagementService.OpenRequest(null,"kf","站点"),false));
    }
}
