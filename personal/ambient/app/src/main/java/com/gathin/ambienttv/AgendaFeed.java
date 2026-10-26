package com.gathin.ambienttv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/** Fetches only an explicitly configured secret Google Calendar iCal feed. */
final class AgendaFeed {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int TIMEOUT_MS = 15_000;
    private static final String INVALID_URL = "Adresse iCal privée Google invalide";
    private static final String UNAVAILABLE = "Synchronisation de l’agenda indisponible";
    private static final Pattern PATH = Pattern.compile(
            "/calendar/ical/([A-Za-z0-9._~+@%-]{1,1024})/private-[A-Za-z0-9_-]{1,256}/(?:basic|full)\\.ics");

    private AgendaFeed() { }

    static boolean validUrl(String input) {
        try {
            validate(input);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static final class Result {
        final String body;
        final String etag;
        final String lastModified;
        final boolean notModified;

        Result(String body, String etag, String lastModified, boolean notModified) {
            this.body = body;
            this.etag = etag;
            this.lastModified = lastModified;
            this.notModified = notModified;
        }
    }

    /** Never returns an error containing the credential-bearing input. */
    static String validate(String input) {
        if (input == null || input.length() > 4096) throw new IllegalArgumentException(INVALID_URL);
        String candidate = input.trim();
        try {
            URI uri = new URI(candidate);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !("calendar.google.com".equalsIgnoreCase(host) || "www.google.com".equalsIgnoreCase(host))
                    || !host.equalsIgnoreCase(uri.getRawAuthority())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(INVALID_URL);
            }
            Matcher path = PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
            if (!path.matches() || !safeIdentifier(path.group(1))) throw new IllegalArgumentException(INVALID_URL);
            return "https://" + host.toLowerCase(Locale.ROOT) + uri.getRawPath();
        } catch (Exception invalid) {
            throw new IllegalArgumentException(INVALID_URL);
        }
    }

    private static boolean safeIdentifier(String identifier) {
        if (".".equals(identifier) || "..".equals(identifier)) return false;
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < identifier.length(); i++) {
            if (identifier.charAt(i) != '%') {
                decoded.append(identifier.charAt(i));
                continue;
            }
            if (i + 2 >= identifier.length()) return false;
            int high = Character.digit(identifier.charAt(i + 1), 16);
            int low = Character.digit(identifier.charAt(i + 2), 16);
            if (high < 0 || low < 0) return false;
            int value = high * 16 + low;
            // Keep a single path segment even if a server decodes escaped characters.
            if (value <= 32 || value == 127 || value == '/' || value == '\\' || value == '%') return false;
            decoded.append((char) value);
            i += 2;
        }
        return !".".contentEquals(decoded) && !"..".contentEquals(decoded);
    }

    /** Redirects are refused so a response cannot send the secret to another endpoint. */
    static Result fetch(String input, String etag, String lastModified) throws IOException {
        HttpsURLConnection connection = null;
        try {
            String url = validate(input);
            connection = (HttpsURLConnection) new URI(url).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/calendar");
            connection.setRequestProperty("Accept-Encoding", "identity");
            String conditionalEtag = safeHeader(etag);
            String conditionalModified = safeHeader(lastModified);
            if (conditionalEtag != null) connection.setRequestProperty("If-None-Match", conditionalEtag);
            if (conditionalModified != null) connection.setRequestProperty("If-Modified-Since", conditionalModified);
            int code = connection.getResponseCode();
            String responseEtag = safeHeader(connection.getHeaderField("ETag"));
            String responseModified = safeHeader(connection.getHeaderField("Last-Modified"));
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return new Result(null, responseEtag, responseModified, true);
            }
            if (code != HttpURLConnection.HTTP_OK) throw new IOException(UNAVAILABLE);
            try (InputStream stream = connection.getInputStream()) {
                return new Result(readCalendar(stream, connection.getContentLengthLong()),
                        responseEtag, responseModified, false);
            }
        } catch (Exception unavailable) {
            // Causes and platform network errors can contain the full secret URL.
            throw new IOException(UNAVAILABLE);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static String readCalendar(InputStream stream, long advertisedLength) throws IOException {
        if (advertisedLength > MAX_BYTES) throw new IOException(UNAVAILABLE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                advertisedLength > 0 ? (int) Math.min(advertisedLength, 64 * 1024) : 8192);
        byte[] buffer = new byte[8192];
        for (int count; (count = stream.read(buffer)) != -1; ) {
            if (bytes.size() + (long) count > MAX_BYTES) throw new IOException(UNAVAILABLE);
            bytes.write(buffer, 0, count);
        }
        String body;
        try {
            body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException(UNAVAILABLE);
        }
        String signature = body.startsWith("\uFEFF") ? body.substring(1) : body;
        if (!(signature.startsWith("BEGIN:VCALENDAR\r\n") || signature.startsWith("BEGIN:VCALENDAR\n"))
                || !signature.trim().endsWith("END:VCALENDAR")) throw new IOException(UNAVAILABLE);
        return body;
    }

    private static String safeHeader(String value) {
        if (value == null || value.isEmpty() || value.length() > 1024) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126) return null;
        }
        return value;
    }
}
