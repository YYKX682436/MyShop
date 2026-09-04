import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;

import java.nio.file.Files;
import java.nio.file.Path;

/** Small operational verifier compiled by update-ip2region.sh in a disposable container. */
public final class Ip2RegionXdbVerifier {

    private Ip2RegionXdbVerifier() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected IPv4 and IPv6 XDB paths");
        }
        Path ipv4Path = Path.of(args[0]);
        Path ipv6Path = Path.of(args[1]);
        if (!Files.isRegularFile(ipv4Path) || Files.size(ipv4Path) <= 0
                || !Files.isRegularFile(ipv6Path) || Files.size(ipv6Path) <= 0) {
            throw new IllegalArgumentException("both XDB files must be non-empty regular files");
        }

        Config ipv4 = Config.custom().setCachePolicy(Config.VIndexCache)
                .setSearchers(4).setXdbPath(ipv4Path.toString()).asV4();
        Config ipv6 = Config.custom().setCachePolicy(Config.VIndexCache)
                .setSearchers(4).setXdbPath(ipv6Path.toString()).asV6();
        Ip2Region region = Ip2Region.create(ipv4, ipv6);
        try {
            String ipv4Result = region.search("8.8.8.8");
            String ipv6Result = region.search("2001:4860:4860::8888");
            if (ipv4Result == null || ipv4Result.isBlank()
                    || ipv6Result == null || ipv6Result.isBlank()) {
                throw new IllegalStateException("IPv4 or IPv6 XDB query returned no result");
            }
            System.out.println("IPv4 query: " + ipv4Result);
            System.out.println("IPv6 query: " + ipv6Result);
        } finally {
            region.close();
        }
    }
}
