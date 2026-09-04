package com.myshop.service.impl;

import com.myshop.model.response.IpLocationResponse;
import com.myshop.service.IpLocationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Local-only ip2region adapter. Missing or corrupt XDB files are deliberately
 * non-fatal: the user detail center remains usable and reports an unknown
 * estimate until the operator provisions a valid database.
 */
@Slf4j
@Service
public class Ip2RegionLocationService implements IpLocationService {

    private static final int SEARCHERS = 15;
    private static final String[] MUNICIPALITIES = {"北京市", "上海市", "天津市", "重庆市"};

    private final Path ipv4DbPath;
    private final Path ipv6DbPath;
    private volatile Ip2Region ip2Region;

    @Autowired
    public Ip2RegionLocationService(
            @Value("${ip-geo.ipv4-db-path:/app/ipdb/ip2region_v4.xdb}") String ipv4DbPath,
            @Value("${ip-geo.ipv6-db-path:/app/ipdb/ip2region_v6.xdb}") String ipv6DbPath) {
        this(ipv4DbPath, ipv6DbPath, null);
    }

    /** Package-private seam for deterministic formatter/fail-soft tests. */
    Ip2RegionLocationService(String ipv4DbPath, String ipv6DbPath, Ip2Region initialRegion) {
        this.ipv4DbPath = toPath(ipv4DbPath);
        this.ipv6DbPath = toPath(ipv6DbPath);
        this.ip2Region = initialRegion;
    }

    @PostConstruct
    public void initialize() {
        if (ip2Region != null) {
            return;
        }

        Config ipv4Config = buildConfig(ipv4DbPath, false);
        Config ipv6Config = buildConfig(ipv6DbPath, true);
        if (ipv4Config == null && ipv6Config == null) {
            log.warn("IP location databases are unavailable; IP geography will remain unknown until XDB files are provisioned");
            return;
        }

        try {
            ip2Region = Ip2Region.create(ipv4Config, ipv6Config);
            log.info("IP location databases loaded from configured local XDB files");
        } catch (Exception exception) {
            log.warn("IP location databases could not be loaded; IP geography is disabled", exception);
            ip2Region = null;
        }
    }

    @Override
    public IpLocationResponse resolve(String ipAddress) {
        InetAddress address = parseAddress(ipAddress);
        if (address == null) {
            return IpLocationResponse.unknown();
        }
        if (isPrivateOrReserved(address.getAddress())) {
            return IpLocationResponse.reserved();
        }

        Ip2Region current = ip2Region;
        if (current == null) {
            return IpLocationResponse.unknown();
        }
        try {
            return fromRegion(current.search(address.getHostAddress()));
        } catch (Exception exception) {
            log.debug("IP location lookup failed softly", exception);
            return IpLocationResponse.unknown();
        }
    }

    /** Converts the official pipe-delimited ip2region result to the API contract. */
    static IpLocationResponse fromRegion(String region) {
        if (region == null || region.isBlank()) {
            return IpLocationResponse.unknown();
        }
        String[] parts = region.split("\\|", -1);
        String country = part(parts, 0);
        String province = part(parts, 2);
        String city = part(parts, 3);
        String isp = part(parts, 4);
        if (country == null && province == null && city == null && isp == null) {
            return IpLocationResponse.unknown();
        }
        String displayName = displayName(country, province, city);
        return new IpLocationResponse(country, province, city, isp,
                displayName == null ? "未知" : displayName, true);
    }

    static String displayName(String country, String province, String city) {
        if (province != null && isMunicipality(province)) {
            return province;
        }
        if (isMainland(country) && province != null && city != null) {
            return province + " · " + city;
        }

        List<String> values = new ArrayList<>();
        addIfPresent(values, country);
        addIfPresent(values, province);
        addIfPresent(values, city);
        return values.isEmpty() ? null : String.join(" · ", values);
    }

    private Config buildConfig(Path path, boolean ipv6) {
        if (path == null || !Files.isRegularFile(path)) {
            log.warn("IP location {} XDB is missing: {}", ipv6 ? "IPv6" : "IPv4", path);
            return null;
        }
        try {
            if (Files.size(path) <= 0) {
                log.warn("IP location {} XDB is empty: {}", ipv6 ? "IPv6" : "IPv4", path);
                return null;
            }
            var builder = Config.custom()
                    .setCachePolicy(Config.VIndexCache)
                    .setSearchers(SEARCHERS)
                    .setXdbPath(path.toString());
            return ipv6 ? builder.asV6() : builder.asV4();
        } catch (Exception exception) {
            log.warn("IP location {} XDB failed validation: {}", ipv6 ? "IPv6" : "IPv4", path, exception);
            return null;
        }
    }

    private static Path toPath(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String part(String[] parts, int index) {
        if (index >= parts.length) return null;
        String value = parts[index] == null ? null : parts[index].trim();
        return value == null || value.isBlank() || "0".equals(value) || "-".equals(value) ? null : value;
    }

    private static void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }

    private static boolean isMainland(String country) {
        return country != null && (country.contains("中国") || country.equalsIgnoreCase("China"));
    }

    private static boolean isMunicipality(String province) {
        for (String municipality : MUNICIPALITIES) {
            if (municipality.equals(province)) return true;
        }
        return false;
    }

    private static InetAddress parseAddress(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 45 || value.indexOf('%') >= 0) return null;
        if (value.contains(":")) {
            if (!value.matches("[0-9A-Fa-f:]+")) return null;
        } else if (!value.matches("[0-9.]+")) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            if (value.contains(":")) return address.getAddress().length == 16 ? address : null;
            return address.getAddress().length == 4 && hasFourIpv4Parts(value) ? address : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static boolean hasFourIpv4Parts(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                if (part.isEmpty() || part.length() > 3 || Integer.parseInt(part) > 255) return false;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrivateOrReserved(byte[] bytes) {
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return first == 0 || first == 10 || first == 127
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 192 && second == 0 && third == 0)
                    || (first == 192 && second == 0 && third == 2)
                    || (first == 198 && second >= 18 && second <= 19)
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 224;
        }

        if (bytes.length != 16) return true;
        boolean allZero = true;
        for (byte value : bytes) {
            if (value != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero || isIpv6Loopback(bytes) || isIpv4Mapped(bytes)) return true;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return (first & 0xfe) == 0xfc // unique local
                || (first == 0xfe && (second & 0xc0) == 0x80) // link local
                || first == 0xff // multicast/reserved
                || (first == 0x20 && second == 0x01 && bytes[2] == 0x0d && bytes[3] == (byte) 0xb8);
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) return false;
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean isIpv6Loopback(byte[] bytes) {
        for (int index = 0; index < 15; index++) {
            if (bytes[index] != 0) return false;
        }
        return bytes[15] == 1;
    }

    @PreDestroy
    public void close() {
        Ip2Region current = ip2Region;
        if (current == null) return;
        try {
            current.close();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            log.debug("IP location database close failed softly", exception);
        }
    }
}
