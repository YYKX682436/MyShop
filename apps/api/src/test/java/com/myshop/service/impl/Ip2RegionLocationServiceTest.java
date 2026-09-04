package com.myshop.service.impl;

import com.myshop.model.response.IpLocationResponse;
import org.junit.jupiter.api.Test;
import org.lionsoul.ip2region.service.Ip2Region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Ip2RegionLocationServiceTest {

    @Test
    void formatsMainlandAndMunicipalityResultsWithoutDuplicatingCity() {
        IpLocationResponse mainland = Ip2RegionLocationService.fromRegion(
                "中国|0|广东省|深圳市|电信");
        IpLocationResponse municipality = Ip2RegionLocationService.fromRegion(
                "中国|0|北京市|北京市|联通");
        IpLocationResponse foreign = Ip2RegionLocationService.fromRegion(
                "美国|0|加利福尼亚州|洛杉矶|电信");

        assertEquals("广东省 · 深圳市", mainland.displayName());
        assertEquals("北京市", municipality.displayName());
        assertEquals("美国 · 加利福尼亚州 · 洛杉矶", foreign.displayName());
        assertTrue(mainland.resolved());
    }

    @Test
    void missingDatabasesFailSoftForBothAddressFamilies() {
        Ip2RegionLocationService service = new Ip2RegionLocationService(
                "C:\\definitely-missing\\ip2region_v4.xdb",
                "C:\\definitely-missing\\ip2region_v6.xdb",
                null);
        service.initialize();

        assertEquals("未知", service.resolve("8.8.8.8").displayName());
        assertEquals("未知", service.resolve("2001:4860:4860::8888").displayName());
        assertFalse(service.resolve("8.8.8.8").resolved());
    }

    @Test
    void privateLoopbackAndReservedAddressesNeverReachTheDatabase() throws Exception {
        Ip2Region region = mock(Ip2Region.class);
        Ip2RegionLocationService service = new Ip2RegionLocationService(
                "unused-v4", "unused-v6", region);

        assertEquals("内网/保留地址", service.resolve("10.0.0.1").displayName());
        assertEquals("内网/保留地址", service.resolve("127.0.0.1").displayName());
        assertEquals("内网/保留地址", service.resolve("192.0.2.1").displayName());
        assertEquals("内网/保留地址", service.resolve("::1").displayName());
        assertEquals("内网/保留地址", service.resolve("fe80::1").displayName());
        assertEquals("内网/保留地址", service.resolve("2001:db8::1").displayName());
        verifyNoInteractions(region);
    }

    @Test
    void validIpv4AndIpv6UseTheReplaceableLocalAdapter() throws Exception {
        Ip2Region region = mock(Ip2Region.class);
        when(region.search(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains(":")
                        ? "美国|0|加利福尼亚州|洛杉矶|电信"
                        : "中国|0|广东省|深圳市|电信");
        Ip2RegionLocationService service = new Ip2RegionLocationService(
                "unused-v4", "unused-v6", region);

        assertEquals("广东省 · 深圳市", service.resolve("1.1.1.1").displayName());
        assertEquals("美国 · 加利福尼亚州 · 洛杉矶",
                service.resolve("2001:4860:4860::8888").displayName());
        verify(region, org.mockito.Mockito.times(2)).search(anyString());
    }

    @Test
    void malformedInputAndEmptyRegionAreUnknown() throws Exception {
        Ip2Region region = mock(Ip2Region.class);
        when(region.search(anyString())).thenReturn("");
        Ip2RegionLocationService service = new Ip2RegionLocationService(
                "unused-v4", "unused-v6", region);

        assertEquals("未知", service.resolve("not-an-ip").displayName());
        assertEquals("未知", service.resolve("1.1.1.1").displayName());
        assertFalse(Ip2RegionLocationService.fromRegion(null).resolved());
    }
}
