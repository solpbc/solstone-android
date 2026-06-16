// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package org.solpbc.rogbidhello;

import android.content.Context;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import org.conscrypt.Conscrypt;
import org.json.JSONArray;
import org.json.JSONObject;

final class PlLinkClient {
    private static final String TAG = "RogbidPlLink";
    private static final String PAIR_LINK_HOST = "link.solpbc.org";
    private static final String PAIR_LINK_PATH = "/p";
    private static final int DEFAULT_DIRECT_PORT = 7657;
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int FLAG_OPEN = 0x01;
    private static final int FLAG_DATA = 0x02;
    private static final int FLAG_CLOSE = 0x04;
    private static final int FLAG_RESET = 0x08;
    private static final int FLAG_WINDOW = 0x10;
    private static final int FLAG_PING = 0x20;
    private static final int FLAG_PONG = 0x40;
    private static boolean tlsProviderReady = false;
    private static final byte[] ECDSA_WITH_SHA256_OID = new byte[] {
            0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x04, 0x03, 0x02
    };

    private PlLinkClient() {
    }

    static boolean looksLikePairLink(String text) {
        if (text == null) {
            return false;
        }
        try {
            URL url = new URL(text.trim());
            return "https".equals(url.getProtocol())
                    && PAIR_LINK_HOST.equals(url.getHost())
                    && PAIR_LINK_PATH.equals(url.getPath())
                    && url.getRef() != null
                    && !url.getRef().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    static String defaultDeviceLabel() {
        String raw = "rogbid-" + (Build.MODEL == null ? "watch" : Build.MODEL);
        String sanitized = raw.replaceAll("[^A-Za-z0-9_.-]", "-")
                .replaceAll("\\.{2,}", "-")
                .replaceAll("-{2,}", "-");
        while (sanitized.startsWith(".") || sanitized.startsWith("-")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        return sanitized.isEmpty() ? "rogbid-watch" : sanitized.toLowerCase(Locale.US);
    }

    static PlResult pairAndProbe(Context context, String pairLink, String deviceLabel)
            throws Exception {
        DirectPairLink link = parseDirectPairLink(pairLink);
        KeyPair keyPair = generateKeyPair();
        String privateKeyPem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String csrPem = buildCsrPem(deviceLabel, keyPair);

        JSONObject request = new JSONObject();
        request.put("csr", csrPem);
        request.put("device_label", deviceLabel);
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);

        HttpResponse pairHttp;
        boolean handshakePinned;
        try (MuxHttpClient client = MuxHttpClient.connectCertless(link)) {
            handshakePinned = client.handshakePinned;
            pairHttp = client.request(
                    "POST",
                    "/app/link/pair?token=" + link.nonce,
                    "application/json",
                    body);
        }
        if (pairHttp.status != 200) {
            throw new IOException("pair failed HTTP " + pairHttp.status + ": " + pairHttp.bodyText());
        }

        JSONObject response = new JSONObject(pairHttp.bodyText());
        PairState state = PairState.fromPairResponse(
                response,
                privateKeyPem,
                deviceLabel,
                link);
        if (!startsWith(state.caFingerprintBytes, link.caFingerprintPrefix)) {
            throw new SSLException("pair response CA fingerprint did not match QR pin");
        }
        String computedClientFp = "sha256:" + sha256Hex(certificateFromPem(state.clientCertPem).getEncoded());
        if (!computedClientFp.equals(state.fingerprint)) {
            throw new SSLException("pair response client fingerprint mismatch");
        }
        state.write(context);

        DirectEndpoint endpoint = state.firstEndpointOr(link.endpoint());
        HttpResponse statusHttp;
        try (MuxHttpClient client = MuxHttpClient.connectAuthenticated(endpoint, state)) {
            statusHttp = client.request("GET", "/app/link/api/status", null, new byte[0]);
        }

        return new PlResult(
                state,
                endpoint,
                handshakePinned,
                pairHttp.status,
                statusHttp.status,
                statusHttp.bodyText());
    }

    private static DirectPairLink parseDirectPairLink(String pairLink) throws Exception {
        URL url = new URL(pairLink.trim());
        if (!"https".equals(url.getProtocol())
                || !PAIR_LINK_HOST.equals(url.getHost())
                || !PAIR_LINK_PATH.equals(url.getPath())) {
            throw new IllegalArgumentException("not a solstone pair link");
        }
        String fragment = url.getRef();
        if (fragment == null || fragment.isEmpty()) {
            throw new IllegalArgumentException("pair link missing fragment");
        }
        byte[] decoded = decodeCrockford32(fragment);
        if (decoded.length != 40 || decoded[0] != 0x04 || decoded[1] != 0x01) {
            throw new IllegalArgumentException("unsupported pair link payload");
        }
        int a = decoded[2] & 0xff;
        int b = decoded[3] & 0xff;
        int c = decoded[4] & 0xff;
        int d = decoded[5] & 0xff;
        if (!isPrivateOrLinkLocal(a, b)) {
            throw new IllegalArgumentException("pair link is not local/private IPv4");
        }
        String host = a + "." + b + "." + c + "." + d;
        int port = ((decoded[6] & 0xff) << 8) | (decoded[7] & 0xff);
        byte[] nonceBytes = Arrays.copyOfRange(decoded, 8, 24);
        byte[] caFp = Arrays.copyOfRange(decoded, 24, 40);
        return new DirectPairLink(host, port, hex(nonceBytes), caFp);
    }

    private static boolean isPrivateOrLinkLocal(int a, int b) {
        return a == 10
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || (a == 169 && b == 254);
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return generator.generateKeyPair();
    }

    private static String buildCsrPem(String label, KeyPair keyPair) throws Exception {
        byte[] certificationRequestInfo = der(
                0x30,
                concat(
                        der(0x02, new byte[] {0x00}),
                        new X500Principal("CN=" + safeDnValue(label)).getEncoded(),
                        keyPair.getPublic().getEncoded(),
                        der(0xa0, new byte[0])));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(certificationRequestInfo);
        byte[] signed = signature.sign();
        byte[] request = der(
                0x30,
                concat(
                        certificationRequestInfo,
                        der(0x30, ECDSA_WITH_SHA256_OID),
                        der(0x03, concat(new byte[] {0x00}, signed))));
        return pem("CERTIFICATE REQUEST", request);
    }

    private static String safeDnValue(String raw) {
        String value = raw == null ? "rogbid-watch" : raw;
        value = value.replaceAll("[^A-Za-z0-9_.-]", "-");
        if (value.isEmpty()) {
            value = "rogbid-watch";
        }
        if (value.length() > 64) {
            value = value.substring(0, 64);
        }
        return value;
    }

    private static String pem(String type, byte[] der) {
        String encoded = Base64.encodeToString(der, Base64.NO_WRAP);
        StringBuilder out = new StringBuilder();
        out.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < encoded.length(); i += 64) {
            out.append(encoded, i, Math.min(encoded.length(), i + 64)).append('\n');
        }
        out.append("-----END ").append(type).append("-----\n");
        return out.toString();
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeDerLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        int bytes = 0;
        int value = length;
        while (value > 0) {
            bytes += 1;
            value >>= 8;
        }
        out.write(0x80 | bytes);
        for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
            out.write((length >> shift) & 0xff);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static byte[] decodeCrockford32(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            int value = crockfordValue(text.charAt(i));
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            while (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
                buffer &= (1 << bits) - 1;
            }
        }
        if (bits > 0 && (buffer & ((1 << bits) - 1)) != 0) {
            throw new IllegalArgumentException("non-zero trailing pair-link pad bits");
        }
        return out.toByteArray();
    }

    private static int crockfordValue(char raw) {
        if (raw == '-' || Character.isWhitespace(raw)) {
            return -1;
        }
        char c = Character.toUpperCase(raw);
        if (c == 'I' || c == 'L') {
            c = '1';
        } else if (c == 'O') {
            c = '0';
        }
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        int value = alphabet.indexOf(c);
        if (value < 0) {
            throw new IllegalArgumentException("bad Crockford base32 char: " + raw);
        }
        return value;
    }

    private static X509Certificate certificateFromPem(String pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static PrivateKey privateKeyFromPem(String pem) throws Exception {
        byte[] der = pemToDer(pem, "PRIVATE KEY");
        KeyFactory factory = KeyFactory.getInstance("EC");
        return factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] pemToDer(String pem, String type) {
        String normalized = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s+", "");
        return Base64.decode(normalized, Base64.DEFAULT);
    }

    private static SSLSocketFactory trustAllFactory() throws Exception {
        SSLContext context = newTlsContext();
        TrustManager[] trustManagers = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        context.init(null, trustManagers, new SecureRandom());
        return context.getSocketFactory();
    }

    private static SSLSocketFactory authenticatedFactory(PairState state) throws Exception {
        SSLContext context = newTlsContext();
        char[] password = "rogbid-pl".toCharArray();
        X509Certificate clientCert = certificateFromPem(state.clientCertPem);
        List<Certificate> keyChain = new ArrayList<>();
        keyChain.add(clientCert);
        for (String caPem : state.caChain) {
            keyChain.add(certificateFromPem(caPem));
        }

        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
        keys.load(null);
        PrivateKey privateKey = privateKeyFromPem(state.privateKeyPem);
        if (!(privateKey instanceof ECPrivateKey)) {
            throw new SSLException("stored PL private key is not EC");
        }
        keys.setKeyEntry(
                "client",
                privateKey,
                password,
                keyChain.toArray(new Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, password);

        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null);
        for (int i = 0; i < state.caChain.size(); i++) {
            trust.setCertificateEntry("ca-" + i, certificateFromPem(state.caChain.get(i)));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);

        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return context.getSocketFactory();
    }

    private static synchronized SSLContext newTlsContext() throws Exception {
        if (!tlsProviderReady) {
            boolean present = false;
            for (java.security.Provider provider : Security.getProviders()) {
                if ("Conscrypt".equals(provider.getName())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1);
            }
            tlsProviderReady = true;
        }
        try {
            return SSLContext.getInstance("TLS", "Conscrypt");
        } catch (Exception error) {
            Log.w(TAG, "Conscrypt TLS provider unavailable, falling back", error);
            return SSLContext.getInstance("TLS");
        }
    }

    private static void enableTls13(SSLSocket socket) throws SSLException {
        List<String> supported = Arrays.asList(socket.getSupportedProtocols());
        if (!supported.contains("TLSv1.3")) {
            throw new SSLException("TLSv1.3 not supported by Android provider: " + supported);
        }
        socket.setEnabledProtocols(new String[] {"TLSv1.3"});
    }

    private static boolean peerChainMatchesPrefix(SSLSession session, byte[] prefix) {
        try {
            Certificate[] chain = session.getPeerCertificates();
            for (Certificate certificate : chain) {
                if (startsWith(sha256Bytes(certificate.getEncoded()), prefix)) {
                    return true;
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "could not inspect TLS peer chain", error);
        }
        return false;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] sha256Bytes(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return hex(sha256Bytes(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }

    private static byte[] httpRequestBytes(
            String method,
            String path,
            String contentType,
            byte[] body) {
        byte[] bodyBytes = body == null ? new byte[0] : body;
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("host: spl.local\r\n");
        head.append("accept: application/json\r\n");
        if (contentType != null && !contentType.isEmpty()) {
            head.append("content-type: ").append(contentType).append("\r\n");
        }
        head.append("content-length: ").append(bodyBytes.length).append("\r\n");
        head.append("\r\n");
        return concat(head.toString().getBytes(StandardCharsets.US_ASCII), bodyBytes);
    }

    private static HttpResponse parseHttpResponse(byte[] raw) throws IOException {
        byte[] marker = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        int split = indexOf(raw, marker);
        if (split < 0) {
            throw new IOException("HTTP response missing header terminator");
        }
        String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("HTTP response missing status line");
        }
        String[] statusParts = lines[0].split(" ", 3);
        if (statusParts.length < 2) {
            throw new IOException("bad HTTP status line: " + lines[0]);
        }
        int status = Integer.parseInt(statusParts[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            headers.put(
                    lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                    lines[i].substring(colon + 1).trim());
        }
        byte[] body = Arrays.copyOfRange(raw, split + marker.length, raw.length);
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            body = dechunk(body);
        } else if (headers.containsKey("content-length")) {
            int length = Integer.parseInt(headers.get("content-length"));
            if (length < body.length) {
                body = Arrays.copyOf(body, length);
            }
        }
        return new HttpResponse(status, headers, body);
    }

    private static byte[] dechunk(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int index = 0;
        while (index < raw.length) {
            int lineEnd = indexOf(raw, "\r\n".getBytes(StandardCharsets.US_ASCII), index);
            if (lineEnd < 0) {
                throw new IOException("chunked body missing size line");
            }
            String sizeText = new String(raw, index, lineEnd - index, StandardCharsets.US_ASCII)
                    .split(";", 2)[0]
                    .trim();
            int size = Integer.parseInt(sizeText, 16);
            index = lineEnd + 2;
            if (size == 0) {
                return out.toByteArray();
            }
            if (index + size > raw.length) {
                throw new IOException("chunked body truncated");
            }
            out.write(raw, index, size);
            index += size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle, 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int start) {
        outer:
        for (int i = Math.max(0, start); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(out, offset, length - offset);
            if (read < 0) {
                throw new IOException("socket closed while reading frame");
            }
            offset += read;
        }
        return out;
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    static final class PlResult {
        final PairState state;
        final DirectEndpoint endpoint;
        final boolean handshakePinned;
        final int pairStatus;
        final int apiStatus;
        final String apiBody;

        PlResult(
                PairState state,
                DirectEndpoint endpoint,
                boolean handshakePinned,
                int pairStatus,
                int apiStatus,
                String apiBody) {
            this.state = state;
            this.endpoint = endpoint;
            this.handshakePinned = handshakePinned;
            this.pairStatus = pairStatus;
            this.apiStatus = apiStatus;
            this.apiBody = apiBody;
        }
    }

    static final class DirectPairLink {
        final String host;
        final int port;
        final String nonce;
        final byte[] caFingerprintPrefix;

        DirectPairLink(String host, int port, String nonce, byte[] caFingerprintPrefix) {
            this.host = host;
            this.port = port;
            this.nonce = nonce;
            this.caFingerprintPrefix = caFingerprintPrefix;
        }

        DirectEndpoint endpoint() {
            return new DirectEndpoint(host, port <= 0 ? DEFAULT_DIRECT_PORT : port);
        }
    }

    static final class DirectEndpoint {
        final String host;
        final int port;

        DirectEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    static final class PairState {
        final String privateKeyPem;
        final String clientCertPem;
        final List<String> caChain;
        final String instanceId;
        final String homeLabel;
        final String homeAttestation;
        final String fingerprint;
        final String localEndpointsJson;
        final byte[] caFingerprintBytes;
        final String deviceLabel;

        PairState(
                String privateKeyPem,
                String clientCertPem,
                List<String> caChain,
                String instanceId,
                String homeLabel,
                String homeAttestation,
                String fingerprint,
                String localEndpointsJson,
                byte[] caFingerprintBytes,
                String deviceLabel) {
            this.privateKeyPem = privateKeyPem;
            this.clientCertPem = clientCertPem;
            this.caChain = caChain;
            this.instanceId = instanceId;
            this.homeLabel = homeLabel;
            this.homeAttestation = homeAttestation;
            this.fingerprint = fingerprint;
            this.localEndpointsJson = localEndpointsJson;
            this.caFingerprintBytes = caFingerprintBytes;
            this.deviceLabel = deviceLabel;
        }

        static PairState fromPairResponse(
                JSONObject response,
                String privateKeyPem,
                String deviceLabel,
                DirectPairLink link) throws Exception {
            JSONArray rawChain = response.getJSONArray("ca_chain");
            List<String> chain = new ArrayList<>();
            for (int i = 0; i < rawChain.length(); i++) {
                chain.add(rawChain.getString(i));
            }
            if (chain.isEmpty()) {
                throw new SSLException("pair response missing CA chain");
            }
            byte[] caFp = sha256Bytes(certificateFromPem(chain.get(0)).getEncoded());
            JSONArray endpoints = response.optJSONArray("local_endpoints");
            if (endpoints == null || endpoints.length() == 0) {
                endpoints = new JSONArray();
                JSONObject fallback = new JSONObject();
                fallback.put("ip", link.host);
                fallback.put("port", link.port);
                fallback.put("scope", "qr");
                endpoints.put(fallback);
            }
            return new PairState(
                    privateKeyPem,
                    response.getString("client_cert"),
                    chain,
                    response.getString("instance_id"),
                    response.optString("home_label", ""),
                    response.getString("home_attestation"),
                    response.getString("fingerprint"),
                    endpoints.toString(),
                    caFp,
                    deviceLabel);
        }

        DirectEndpoint firstEndpointOr(DirectEndpoint fallback) {
            try {
                JSONArray endpoints = new JSONArray(localEndpointsJson);
                for (int i = 0; i < endpoints.length(); i++) {
                    JSONObject item = endpoints.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String host = item.optString("ip", item.optString("host", "")).trim();
                    int port = item.optInt("port", DEFAULT_DIRECT_PORT);
                    if (!host.isEmpty()) {
                        return new DirectEndpoint(host, port);
                    }
                }
            } catch (Exception ignored) {
                // Fall through to the QR endpoint.
            }
            return fallback;
        }

        void write(Context context) throws Exception {
            File dir = new File(context.getFilesDir(), "pl-link");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("could not create " + dir);
            }
            writeFile(new File(dir, "private.pem"), privateKeyPem);
            writeFile(new File(dir, "cert.pem"), clientCertPem);
            StringBuilder chainPem = new StringBuilder();
            for (String item : caChain) {
                chainPem.append(item.endsWith("\n") ? item : item + "\n");
            }
            writeFile(new File(dir, "chain.pem"), chainPem.toString());
            writeFile(new File(dir, "home_attestation.jwt"), homeAttestation);

            JSONObject peer = new JSONObject();
            peer.put("label", deviceLabel);
            peer.put("paired_at", timestamp());
            peer.put("instance_id", instanceId);
            peer.put("home_label", homeLabel);
            peer.put("fingerprint", "sha256:" + hex(caFingerprintBytes));
            peer.put("client_fingerprint", fingerprint);
            peer.put("local_endpoints", new JSONArray(localEndpointsJson));
            peer.put("role", "");
            writeFile(new File(dir, "peer.json"), peer.toString(2) + "\n");
        }

        private static void writeFile(File file, String text) throws IOException {
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static final class HttpResponse {
        final int status;
        final Map<String, String> headers;
        final byte[] body;

        HttpResponse(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static final class Frame {
        final int streamId;
        final int flags;
        final byte[] payload;

        Frame(int streamId, int flags, byte[] payload) {
            this.streamId = streamId;
            this.flags = flags;
            this.payload = payload;
        }
    }

    static final class MuxHttpClient implements Closeable {
        private final SSLSocket socket;
        private final InputStream input;
        private final OutputStream output;
        private int nextStreamId = 1;
        final boolean handshakePinned;

        private MuxHttpClient(SSLSocket socket, boolean handshakePinned) throws IOException {
            this.socket = socket;
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
            this.handshakePinned = handshakePinned;
        }

        static MuxHttpClient connectCertless(DirectPairLink link) throws Exception {
            SSLSocket socket = (SSLSocket) trustAllFactory().createSocket(link.host, link.port);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            enableTls13(socket);
            socket.startHandshake();
            boolean pinned = peerChainMatchesPrefix(socket.getSession(), link.caFingerprintPrefix);
            if (!pinned) {
                socket.close();
                throw new SSLException("pair TLS peer chain did not match QR CA pin");
            }
            return new MuxHttpClient(socket, pinned);
        }

        static MuxHttpClient connectAuthenticated(DirectEndpoint endpoint, PairState state)
                throws Exception {
            SSLSocket socket = (SSLSocket) authenticatedFactory(state).createSocket(
                    endpoint.host,
                    endpoint.port);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            enableTls13(socket);
            socket.startHandshake();
            return new MuxHttpClient(socket, true);
        }

        HttpResponse request(String method, String path, String contentType, byte[] body)
                throws IOException {
            int streamId = nextStreamId;
            nextStreamId += 2;
            byte[] request = httpRequestBytes(method, path, contentType, body);
            writeFrame(streamId, FLAG_OPEN | FLAG_DATA, request);
            writeFrame(streamId, FLAG_CLOSE, new byte[0]);

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            while (true) {
                Frame frame = readFrame();
                if (frame.streamId == 0) {
                    handleControl(frame);
                    continue;
                }
                if (frame.streamId != streamId) {
                    if ((frame.flags & FLAG_OPEN) != 0) {
                        writeFrame(frame.streamId, FLAG_RESET, new byte[] {0x01});
                    }
                    continue;
                }
                if ((frame.flags & FLAG_DATA) != 0) {
                    if (response.size() + frame.payload.length > MAX_RESPONSE_BYTES) {
                        writeFrame(streamId, FLAG_RESET, new byte[] {0x05});
                        throw new IOException("PL response exceeded " + MAX_RESPONSE_BYTES + " bytes");
                    }
                    response.write(frame.payload, 0, frame.payload.length);
                }
                if ((frame.flags & FLAG_RESET) != 0) {
                    int reason = frame.payload.length > 0 ? frame.payload[0] & 0xff : 0xff;
                    throw new IOException("PL stream reset: " + reason);
                }
                if ((frame.flags & FLAG_WINDOW) != 0) {
                    continue;
                }
                if ((frame.flags & FLAG_CLOSE) != 0) {
                    return parseHttpResponse(response.toByteArray());
                }
            }
        }

        private void handleControl(Frame frame) throws IOException {
            if ((frame.flags & FLAG_PING) != 0 && frame.payload.length == 8) {
                writeFrame(0, FLAG_PONG, frame.payload);
            }
        }

        private Frame readFrame() throws IOException {
            try {
                byte[] header = readExactly(input, 8);
                int streamId = ((header[0] & 0xff) << 24)
                        | ((header[1] & 0xff) << 16)
                        | ((header[2] & 0xff) << 8)
                        | (header[3] & 0xff);
                int flags = header[4] & 0xff;
                int length = ((header[5] & 0xff) << 16)
                        | ((header[6] & 0xff) << 8)
                        | (header[7] & 0xff);
                return new Frame(streamId, flags, readExactly(input, length));
            } catch (SocketTimeoutException error) {
                throw new IOException("timed out waiting for PL frame", error);
            }
        }

        private void writeFrame(int streamId, int flags, byte[] payload) throws IOException {
            if (payload.length > 0xffffff) {
                throw new IOException("PL frame payload too large: " + payload.length);
            }
            byte[] header = new byte[8];
            header[0] = (byte) ((streamId >> 24) & 0xff);
            header[1] = (byte) ((streamId >> 16) & 0xff);
            header[2] = (byte) ((streamId >> 8) & 0xff);
            header[3] = (byte) (streamId & 0xff);
            header[4] = (byte) flags;
            header[5] = (byte) ((payload.length >> 16) & 0xff);
            header[6] = (byte) ((payload.length >> 8) & 0xff);
            header[7] = (byte) (payload.length & 0xff);
            output.write(header);
            output.write(payload);
            output.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
