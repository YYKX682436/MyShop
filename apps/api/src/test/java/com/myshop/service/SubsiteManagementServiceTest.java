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
    @Test void insufficientBalanceDoesNotCreateOrDebit() {
        var sites=mock(SubsiteRepository.class); var settings=mock(SubsiteSettingsRepository.class);
        var users=mock(UserRepository.class); var levels=mock(UserLevelRepository.class);
        var jdbc=mock(JdbcTemplate.class); var ledger=mock(BalanceTransactionRepository.class);
        var service=new SubsiteManagementService(sites,settings,users,levels,jdbc,ledger);
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
