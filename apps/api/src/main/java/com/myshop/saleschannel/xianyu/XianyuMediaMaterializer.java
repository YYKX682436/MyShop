package com.myshop.saleschannel.xianyu;

import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.service.UploadStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Reads managed MyShop uploads locally and validates any administrator supplied public URL. */
@Component
@RequiredArgsConstructor
public class XianyuMediaMaterializer {

    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final List<String> MIME_TYPES = List.of("image/jpeg", "image/png", "image/webp");

    private final UploadStorage uploadStorage;
    @Value("${upload.url-prefix:/api/uploads}")
    private String uploadUrlPrefix;

    public MaterializedImage readImage(String source) {
        if (source == null || source.isBlank()) throw permanent("INVALID_IMAGE_URL", "Image URL is empty");
        if (isManagedUpload(source)) return readManaged(source);
        return downloadExternal(source);
    }

    private boolean isManagedUpload(String source) {
        return source.startsWith(normalizedPrefix() + "/");
    }

    private MaterializedImage readManaged(String source) {
        String relative = source.substring(normalizedPrefix().length() + 1);
        if (relative.contains("?") || relative.contains("#") || relative.indexOf('\\') >= 0
                || relative.matches("(?i).*(%2f|%5c).*")) {
            throw permanent("INVALID_IMAGE_PATH", "Managed image path is invalid");
        }
        final Path path;
        try {
            path = uploadStorage.resolveManagedPath(relative);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw permanent("IMAGE_NOT_FOUND", "Managed image is not a regular file");
            }
            Path realRoot = uploadStorage.directory().toRealPath();
            Path realFile = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realFile.startsWith(realRoot)) throw permanent("INVALID_IMAGE_PATH", "Managed image path is outside storage");
            long size = Files.size(realFile);
            if (size <= 0 || size > MAX_IMAGE_BYTES) throw permanent("INVALID_IMAGE_SIZE", "Image size is out of range");
            byte[] content = Files.readAllBytes(realFile);
            String mime = detectImageType(content);
            if (!allowedMime(mime)) throw permanent("INVALID_IMAGE_TYPE", "Managed image must be JPEG, PNG, or WebP");
            return new MaterializedImage(realFile.getFileName().toString(), mime, content);
        } catch (IOException | SecurityException exception) {
            throw retryable("IMAGE_READ_FAILED", "Managed image could not be read");
        }
    }

    private MaterializedImage downloadExternal(String source) {
        final URI uri;
        final InetAddress[] resolvedAddresses;
        try {
            uri = URI.create(source.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) {
                throw permanent("INVALID_IMAGE_URL", "Image URL must be a public HTTP(S) URL");
            }
            resolvedAddresses = validatePublicHost(uri.getHost());
        } catch (SalesChannelProviderClientException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw permanent("INVALID_IMAGE_URL", "Image URL is invalid");
        }

        Response response = fetchPinned(uri, resolvedAddresses);
        if (response.status() != 200) {
            if (response.status() >= 500 || response.status() == 429) {
                throw retryable("IMAGE_FETCH_FAILED", "Image host temporarily rejected the request");
            }
            throw permanent("IMAGE_FETCH_FAILED", "Image host did not return an image");
        }
        String declaredMime = response.headers().getOrDefault("content-type", "")
                .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!allowedMime(declaredMime)) throw permanent("INVALID_IMAGE_TYPE", "Remote image must be JPEG, PNG, or WebP");
        byte[] bytes = response.body();
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw permanent("INVALID_IMAGE_SIZE", "Remote image size is out of range");
        }
        String mime = detectImageType(bytes);
        if (!allowedMime(mime) || !mime.equals(declaredMime)) {
            throw permanent("INVALID_IMAGE_TYPE", "Remote image content does not match its declared type");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String filename = path.substring(path.lastIndexOf('/') + 1);
        if (filename.isBlank() || filename.length() > 128 || filename.contains("\\")) filename = "image";
        return new MaterializedImage(filename, mime, bytes);
    }

    private InetAddress[] validatePublicHost(String host) {
        String normalized = unbracket(host).toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal")
                || normalized.equals("metadata") || normalized.startsWith("metadata.")) {
            throw permanent("IMAGE_URL_BLOCKED", "Private or metadata host names are not allowed");
        }
        final InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(normalized);
        } catch (UnknownHostException exception) {
            throw retryable("IMAGE_DNS_FAILED", "Image host could not be resolved");
        }
        if (resolved.length == 0 || Arrays.stream(resolved).anyMatch(address -> !isPublicAddress(address))) {
            throw permanent("IMAGE_URL_BLOCKED", "Image host resolves to a non-public address");
        }
        return resolved;
    }

    /** Connects directly to the already validated address while preserving TLS SNI and hostname checks. */
    Response fetchPinned(URI uri, InetAddress[] resolvedAddresses) {
        int port = uri.getPort() == -1 ? (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80) : uri.getPort();
        for (InetAddress address : resolvedAddresses) {
            try (Socket rawSocket = new Socket()) {
                rawSocket.connect(new InetSocketAddress(address, port), (int) Duration.ofSeconds(8).toMillis());
                rawSocket.setSoTimeout((int) Duration.ofSeconds(15).toMillis());
                Socket socket = rawSocket;
                if (uri.getScheme().equalsIgnoreCase("https")) {
                    String tlsHost = unbracket(uri.getHost());
                    SSLSocket sslSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                            .createSocket(rawSocket, tlsHost, port, true);
                    SSLParameters parameters = sslSocket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!isIpLiteral(tlsHost)) parameters.setServerNames(List.of(new SNIHostName(tlsHost)));
                    sslSocket.setSSLParameters(parameters);
                    sslSocket.setSoTimeout((int) Duration.ofSeconds(15).toMillis());
                    sslSocket.startHandshake();
                    socket = sslSocket;
                }
                return requestImage(uri, socket);
            } catch (SSLHandshakeException exception) {
                throw permanent("IMAGE_TLS_FAILED", "Image host TLS identity could not be verified");
            } catch (IOException ignored) { }
        }
        throw retryable("IMAGE_FETCH_FAILED", "Image host could not be reached");
    }

    private Response requestImage(URI uri, Socket socket) throws IOException {
        String host = unbracket(uri.getHost());
        String hostHeader = host.contains(":") ? "[" + host + "]" : host;
        int port = uri.getPort();
        if (port != -1 && port != (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80)) {
            hostHeader += ":" + port;
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String request = "GET " + path + " HTTP/1.1\r\nHost: " + hostHeader
                + "\r\nAccept: image/jpeg,image/png,image/webp\r\nAccept-Encoding: identity"
                + "\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();

        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        String statusLine = readHttpLine(input, 8_192);
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2 || !statusParts[0].matches("HTTP/1\\.[01]")) {
            throw new IOException("Invalid HTTP response status");
        }
        int status;
        try {
            status = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid HTTP response status", exception);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        int headerBytes = 0;
        String line;
        while (!(line = readHttpLine(input, 8_192)).isEmpty()) {
            headerBytes += line.length();
            if (headerBytes > 32_768) throw new IOException("HTTP response headers are too large");
            int separator = line.indexOf(':');
            if (separator <= 0) throw new IOException("Invalid HTTP response header");
            headers.putIfAbsent(line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    line.substring(separator + 1).trim());
        }
        String encoding = headers.getOrDefault("content-encoding", "identity");
        if (!encoding.equalsIgnoreCase("identity")) {
            throw permanent("IMAGE_ENCODING_UNSUPPORTED", "Compressed image responses are not accepted");
        }
        byte[] body;
        String transferEncoding = headers.getOrDefault("transfer-encoding", "");
        if (!transferEncoding.isEmpty()) {
            if (!transferEncoding.equalsIgnoreCase("chunked")) throw new IOException("Unsupported transfer encoding");
            body = readChunkedBody(input);
        } else if (headers.containsKey("content-length")) {
            long length;
            try {
                length = Long.parseLong(headers.get("content-length"));
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid content length", exception);
            }
            if (length < 0 || length > MAX_IMAGE_BYTES) {
                throw permanent("INVALID_IMAGE_SIZE", "Remote image size is out of range");
            }
            body = input.readNBytes((int) length);
            if (body.length != length) throw new IOException("Image response body ended early");
        } else {
            body = input.readNBytes(MAX_IMAGE_BYTES + 1);
        }
        return new Response(status, headers, body);
    }

    private byte[] readChunkedBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readHttpLine(input, 8_192);
            int extension = sizeLine.indexOf(';');
            String sizeValue = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim();
            long size;
            try {
                size = Long.parseLong(sizeValue, 16);
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid chunk size", exception);
            }
            if (size < 0 || size > MAX_IMAGE_BYTES - output.size()) {
                throw permanent("INVALID_IMAGE_SIZE", "Remote image size is out of range");
            }
            if (size == 0) {
                while (!readHttpLine(input, 8_192).isEmpty()) { /* consume trailers */ }
                return output.toByteArray();
            }
            byte[] chunk = input.readNBytes((int) size);
            if (chunk.length != size) throw new IOException("Chunked image response ended early");
            output.write(chunk);
            if (input.read() != '\r' || input.read() != '\n') throw new IOException("Invalid chunk terminator");
        }
    }

    private String readHttpLine(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (line.size() >= maxBytes) throw new IOException("HTTP response line is too long");
            line.write(value);
        }
        if (value == -1 && line.size() == 0) throw new IOException("Unexpected end of HTTP response");
        byte[] bytes = line.toByteArray();
        int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
    }

    private String unbracket(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    private boolean isIpLiteral(String host) {
        return host != null && (host.contains(":") || host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"));
    }

    record Response(int status, Map<String, String> headers, byte[] body) { }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = bytes[0] & 0xff, b = bytes[1] & 0xff, c = bytes[2] & 0xff;
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
            if (a == 100 && b >= 64 && b <= 127) return false;
            if (a == 169 && b == 254) return false;
            if (a == 172 && b >= 16 && b <= 31) return false;
            if (a == 192 && (b == 0 || b == 168) || a == 192 && b == 88 && c == 99) return false;
            if (a == 198 && (b == 18 || b == 19 || b == 51) || a == 203 && b == 0 && c == 113) return false;
            return true;
        }
        if (address instanceof Inet6Address) {
            // Public global unicast currently occupies 2000::/3. Reject ULA, link-local,
            // IPv4-mapped, documentation and other special-use ranges by default.
            if ((bytes[0] & 0xe0) != 0x20) return false;
            if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01) {
                int secondHextet = ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
                if (secondHextet < 0x0200 || secondHextet == 0x0db8) return false;
            }
            if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x02) return false;
            return !((bytes[0] & 0xff) == 0x3f && (bytes[1] & 0xff) == 0xff
                    && (bytes[2] & 0xf0) == 0x00);
        }
        return false;
    }

    private boolean allowedMime(String mime) { return mime != null && MIME_TYPES.contains(mime.toLowerCase()); }

    private String detectImageType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && (bytes[4] & 0xff) == 0x0d && (bytes[5] & 0xff) == 0x0a
                && (bytes[6] & 0xff) == 0x1a && (bytes[7] & 0xff) == 0x0a) return "image/png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return "application/octet-stream";
    }

    private String normalizedPrefix() {
        String prefix = uploadUrlPrefix == null || uploadUrlPrefix.isBlank() ? "/api/uploads" : uploadUrlPrefix.trim();
        while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        return prefix.isEmpty() ? "/api/uploads" : prefix;
    }

    private static SalesChannelProviderClientException retryable(String code, String message) {
        return new SalesChannelProviderClientException(
                SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, code, message);
    }

    private static SalesChannelProviderClientException permanent(String code, String message) {
        return new SalesChannelProviderClientException(
                SalesChannelProviderClientException.Disposition.SAFE_PERMANENT, code, message);
    }

    public record MaterializedImage(String filename, String contentType, byte[] content) {
        public MaterializedImage { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
}
