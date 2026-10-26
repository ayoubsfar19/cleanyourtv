package com.gathin.ambienttv;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class AgendaFeedTest {
    private static final String PATH = "/calendar/ical/example%40gmail.com/private-example_token/basic.ics";
    private static final String URL = "https://calendar.google.com" + PATH;
    private static final String CALENDAR = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n";

    @Test public void acceptsOnlyPrivateGoogleFeedsAndNormalizesSchemeAndHost() {
        assertEquals(URL, AgendaFeed.validate("  HTTPS://CALENDAR.GOOGLE.COM" + PATH + "\n"));
        assertEquals("https://www.google.com" + PATH, AgendaFeed.validate("https://www.google.com" + PATH));
        assertEquals(URL.replace("basic.ics", "full.ics"), AgendaFeed.validate(URL.replace("basic.ics", "full.ics")));
        assertEquals(URL.replace("example%40gmail.com", "example+family@gmail.com"),
                AgendaFeed.validate(URL.replace("example%40gmail.com", "example+family@gmail.com")));
        assertTrue(AgendaFeed.validUrl(URL));
        assertFalse(AgendaFeed.validUrl(null));
    }

    @Test public void rejectsOtherHostsCredentialsPortsQueriesAndFragments() {
        reject(null);
        reject("");
        reject(URL.replace("https:", "http:"));
        reject(URL.replace("calendar.google.com", "google.com"));
        reject(URL.replace("calendar.google.com", "calendar.google.com.evil.test"));
        reject(URL.replace("calendar.google.com", "calendar.google.com."));
        reject(URL.replace("calendar.google.com", "calendar.google.com:443"));
        reject(URL.replace("calendar.google.com", "calendar.google.com:"));
        reject(URL.replace("calendar.google.com", "secret@calendar.google.com"));
        reject(URL + "?secret=value");
        reject(URL + "#secret");
        reject(URL.replace("calendar.google.com", "127.0.0.1"));
    }

    @Test public void rejectsPublicAndMalformedPathsAndEncodedTraversal() {
        reject(URL.replace("private-example_token", "public"));
        reject(URL.replace("private-example_token", "private-"));
        reject(URL.replace("basic.ics", "basic.ics/extra"));
        reject(URL.replace("example%40gmail.com", ".."));
        reject(URL.replace("example%40gmail.com", "%2e%2e"));
        reject(URL.replace("example%40gmail.com", "%2e%2e%2fsecret"));
        reject(URL.replace("example%40gmail.com", "a%5cb"));
        reject(URL.replace("example%40gmail.com", "a%252fb"));
        reject(URL.replace("example%40gmail.com", "a%00b"));
        reject(URL.replace("example%40gmail.com", "a%0Ab"));
        reject(URL.replace("example%40gmail.com", "a%2"));
        reject(URL.replace("example%40gmail.com", "a%XX"));
        reject(URL.replace("example%40gmail.com", "a;b"));
    }

    @Test public void validatesCalendarEnvelopeAndUtf8() throws Exception {
        assertEquals(CALENDAR, read(CALENDAR));
        assertEquals("\uFEFF" + CALENDAR, read("\uFEFF" + CALENDAR));
        rejectBody("<html>BEGIN:VCALENDAR</html>");
        rejectBody("BEGIN:VCALENDAR\nVERSION:2.0\n");
        rejectBody("BEGIN:VCALENDAR\nEND:VCALENDAR\n<html>oops</html>");
        try {
            AgendaFeed.readCalendar(new ByteArrayInputStream(new byte[]{(byte) 0xc3, (byte) 0x28}), -1);
            fail("Invalid UTF-8 must not become a cached feed");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("example_token"));
        }
    }

    @Test public void boundsKnownAndUnknownResponseSizes() throws Exception {
        InputStream unread = new InputStream() {
            @Override public int read() { fail("Oversized advertised body must not be read"); return -1; }
        };
        try {
            AgendaFeed.readCalendar(unread, AgendaFeed.MAX_BYTES + 1L);
            fail("Oversized response accepted");
        } catch (IOException expected) { }
        byte[] large = new byte[AgendaFeed.MAX_BYTES + 1];
        try {
            AgendaFeed.readCalendar(new ByteArrayInputStream(large), -1);
            fail("Unknown-length response exceeded limit");
        } catch (IOException expected) { }
        byte[] prefix = "BEGIN:VCALENDAR\n".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\nEND:VCALENDAR".getBytes(StandardCharsets.UTF_8);
        byte[] exact = new byte[AgendaFeed.MAX_BYTES];
        java.util.Arrays.fill(exact, (byte) ' ');
        System.arraycopy(prefix, 0, exact, 0, prefix.length);
        System.arraycopy(suffix, 0, exact, exact.length - suffix.length, suffix.length);
        assertEquals(AgendaFeed.MAX_BYTES, AgendaFeed.readCalendar(new ByteArrayInputStream(exact), exact.length).length());
    }

    @Test public void validationErrorsNeverIncludeTheCredential() {
        try {
            AgendaFeed.validate(URL + "?invalid=example_token");
            fail("Invalid URL accepted");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("example_token"));
            assertNull(expected.getCause());
        }
    }

    private static void reject(String url) {
        try {
            AgendaFeed.validate(url);
            fail("Unsafe feed address accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static String read(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return AgendaFeed.readCalendar(new ByteArrayInputStream(bytes), bytes.length);
    }

    private static void rejectBody(String value) {
        try {
            read(value);
            fail("Invalid calendar envelope accepted");
        } catch (IOException expected) { }
    }
}
