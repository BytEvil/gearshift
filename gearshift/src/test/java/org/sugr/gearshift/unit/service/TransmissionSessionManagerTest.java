package org.sugr.gearshift.unit.service;

import android.app.Activity;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPreferenceManager;
import org.sugr.gearshift.BuildConfig;
import org.sugr.gearshift.G;
import org.sugr.gearshift.core.Torrent;
import org.sugr.gearshift.core.TransmissionProfile;
import org.sugr.gearshift.core.TransmissionSession;
import org.sugr.gearshift.datasource.DataSource;
import org.sugr.gearshift.datasource.TorrentStatus;
import org.sugr.gearshift.service.ConnectionProvider;
import org.sugr.gearshift.service.TransmissionSessionManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class)
public class TransmissionSessionManagerTest {
    private ConnectivityManager connMananager;
    private SharedPreferences defaultPrefs;
    private TransmissionProfile profile;
    private DataSource dataSource;
    private ConnectionProvider connProvider;
    private HttpsURLConnectionTest connection;
    private TransmissionSessionManager manager;

    @Before public void setUp() throws Exception {
        defaultPrefs = ShadowPreferenceManager.getDefaultSharedPreferences(
                RuntimeEnvironment.application.getApplicationContext());

        connMananager = mock(ConnectivityManager.class);
        when(connMananager.getActiveNetworkInfo()).thenReturn(mock(NetworkInfo.class));

        profile = mock(TransmissionProfile.class);
        when(profile.getTimeout()).thenReturn(1);
        when(profile.getId()).thenReturn("existingId");

        Activity activity = Robolectric.buildActivity(Activity.class).create().get();

        dataSource = new DataSource(activity);
        dataSource.open();

        connProvider = mock(ConnectionProvider.class);
        connection = new HttpsURLConnectionTest(new URL("http://example.com"));
        connection.outputStream = new ByteArrayOutputStream();
        when(connProvider.open(profile)).thenReturn(connection);

        manager = new TransmissionSessionManager(connMananager, defaultPrefs, profile,
            dataSource, connProvider);
    }

    @Test public void connectivity() {
        assertNotNull(manager);

        when(connMananager.getActiveNetworkInfo()).thenReturn(null);
        assertFalse(manager.hasConnectivity());

        when(connMananager.getActiveNetworkInfo()).thenReturn(mock(NetworkInfo.class));
        assertTrue(manager.hasConnectivity());
    }

    @Test public void network() throws Exception {
        assertNotNull(manager);

        setupConnection(HttpURLConnection.HTTP_NOT_FOUND, new HashMap<String, String>(), "", null, "404 not found");

        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals(1000, connection.readTimeout);
            assertEquals(1000, connection.connectTimeout);
            assertFalse(connection.isConnected);
            assertEquals(1, connection.connectCount);
            assertEquals("application/json", connection.requestProperties.get("Content-Type"));
            assertEquals("gzip, deflate", connection.requestProperties.get("Accept-Encoding"));
            assertFalse(connection.useCaches);
            assertFalse(connection.allowUserInteraction);
            assertEquals("POST", connection.requestMethod);
            assertTrue(connection.doInput);
            assertTrue(connection.doOutput);
            assertFalse(connection.requestProperties.containsKey("X-Transmission-Session-Id"));
            assertFalse(connection.requestProperties.containsKey("Authorization"));

            assertEquals("{\"method\":\"session-get\"}", connection.outputStream.toString());

            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, e.getCode());
            assertEquals("404 not found", e.getMessage());
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/html");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "<h1>Hello World</h1>", null, "");

        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals(HttpURLConnection.HTTP_OK, e.getCode());
            assertEquals("no-json", e.getMessage());
        }

        assertEquals(201, HttpURLConnection.HTTP_CREATED);
        setupConnection(HttpURLConnection.HTTP_CREATED, headers, "<h1>Hello World</h1>", null, "");

        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals(HttpURLConnection.HTTP_CREATED, e.getCode());
            assertEquals("no-json", e.getMessage());
        }

        headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\", \"arguments\": {}}", null, "");

        manager.updateSession();

        headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": {}, \"result\": \"success\"}", null, "");

        manager.updateSession();

        final List<Boolean> tries = new ArrayList<>();
        connection.setConnectTest(new Runnable() {
            @Override
            public void run() {
                Map<String, String> headers = new HashMap<>();
                if ("sessid".equals(connection.requestProperties.get("X-Transmission-Session-Id"))) {
                    headers.put("Content-Type", "application/json");
                    setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": {}, \"result\": \"success\"}", null, "");
                    tries.add(true);
                } else {
                    headers.put("X-Transmission-Session-Id", "sessid");
                    headers.put("Content-Type", "text/html");
                    setupConnection(HttpURLConnection.HTTP_CONFLICT, headers, "blabla", null, "");
                    tries.add(false);
                }
            }
        });
        manager.updateSession();

        assertEquals(2, tries.size());
        assertFalse(tries.get(0));
        assertTrue(tries.get(1));

        headers = new HashMap<>();
        connection.setConnectTest(null);
        setupConnection(HttpURLConnection.HTTP_OK, headers, "error", null, "");

        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals(-4, e.getCode());
        }

        when(connMananager.getActiveNetworkInfo()).thenReturn(null);
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": {}, \"result\": \"success\"}", null, "");

        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals("connectivity", e.getMessage());
            assertEquals(-1, e.getCode());
        }
        when(connMananager.getActiveNetworkInfo()).thenReturn(mock(NetworkInfo.class));
    }

    @Test public void delayedNetwork() throws Exception {
        connection = new DelayedHttpsURLConnectionTest(new URL("http://example.com"));
        ((DelayedHttpsURLConnectionTest) connection).setDelay(2);
        when(connProvider.open(profile)).thenReturn(connection);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": {}, \"result\": \"success\"}", null, "");

        boolean timeout = false;
        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals("timeout", e.getMessage());
            assertEquals(-1, e.getCode());
            timeout = true;
        }
        assertTrue(timeout);
    }

    @Test public void sslNetwork() throws Exception {
        when(profile.isUseSSL()).thenReturn(true);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": {}, \"result\": \"success\"}", null, "");

        manager.updateSession();

        assertTrue(connection.socketFactory instanceof SSLSocketFactory);
        assertTrue(connection.hostnameVerifier instanceof HostnameVerifier);

        assertTrue(connection.hostnameVerifier.verify(null, null));

        when(profile.getUsername()).thenReturn("foo");
        when(profile.getPassword()).thenReturn("bar");

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": {}, \"result\": \"success\"}", null, "");

        manager.updateSession();

        assertTrue(connection.requestProperties.containsKey("Authorization"));
        assertEquals("Basic " + Base64.encodeToString(("foo:bar").getBytes(), Base64.DEFAULT),
            connection.requestProperties.get("Authorization"));
    }

    @Test public void session() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers,
            "{\"arguments\": {\"version\": \"2.52\"}, \"result\": \"success\"}", null, "");

        manager.updateSession();

        TransmissionSession session = dataSource.getSession(profile.getId());
        assertNotNull(session);
        assertEquals("2.52", session.getVersion());

        assertNull(session.getDownloadDir());

        session.setDownloadDir("/foo/bar");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");

        manager.setSession(session, TransmissionSession.SetterFields.DOWNLOAD_DIR);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(connection.outputStream.toString());

        assertEquals("session-set", node.path("method").asText());
        assertEquals("/foo/bar", node.path("arguments").path("download-dir").asText());
    }

    @Test public void torrents() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers,
            "{\"arguments\": {\"torrents\": [{\"id\": 10, \"name\": \"fedora 1\", \"status\": 0, \"hashString\": \"foo\"}, {\"id\": 21, \"name\": \"ubuntu 7\", \"status\": 6, \"hashString\": \"bar\"}]}, \"result\": \"success\"}", null, "");

        TorrentStatus status = manager.getActiveTorrents(new String[] {"id", "name", "status"});
        assertNotNull(status);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-get", node.path("method").asText());
        assertEquals("recently-active", node.path("arguments").path("ids").asText());
        assertEquals("id", node.path("arguments").path("fields").path(0).asText());
        assertEquals("name", node.path("arguments").path("fields").path(1).asText());
        assertEquals("status", node.path("arguments").path("fields").path(2).asText());

        Cursor cursor = dataSource.getTorrentCursor(profile.getId(), null, new String[0], null, false);
        assertEquals(2, cursor.getCount());
        cursor.close();

        setupConnection(HttpURLConnection.HTTP_OK, headers,
            "{\"arguments\": {\"torrents\": [{\"id\": 10, \"name\": \"fedora 1\", \"status\": 0, \"hashString\": \"foo\"}, {\"id\": 21, \"name\": \"ubuntu 7\", \"status\": 6, \"hashString\": \"bar\"}, {\"id\": 37, \"name\": \"mint 14\", \"status\": 4, \"hashString\": \"alpha\"}]}, \"result\": \"success\"}", null, "");

        status = manager.getTorrents(new String[] {"id", "name", "status"}, null, false);
        assertNotNull(status);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-get", node.path("method").asText());
        assertNull(node.path("arguments").get("ids"));
        assertEquals("id", node.path("arguments").path("fields").get(0).asText());
        assertEquals("name", node.path("arguments").path("fields").path(1).asText());
        assertEquals("status", node.path("arguments").path("fields").path(2).asText());

        cursor = dataSource.getTorrentCursor(profile.getId(), null, new String[0], null, false);
        assertEquals(3, cursor.getCount());
        cursor.close();

        setupConnection(HttpURLConnection.HTTP_OK, headers,
            "{\"arguments\": {\"torrents\": [{\"id\": 10, \"name\": \"fedora 1\", \"status\": 0, \"hashString\": \"foo\"}]}, \"result\": \"success\"}", null, "");

        status = manager.getTorrents(new String[] {"id", "name", "status"}, new String[] {"foo"}, false);
        assertNotNull(status);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-get", node.path("method").asText());
        assertEquals("foo", node.path("arguments").path("ids").path(0).asText());
        assertNull(node.path("arguments").path("ids").get(1));
        assertEquals("id", node.path("arguments").path("fields").get(0).asText());
        assertEquals("name", node.path("arguments").path("fields").path(1).asText());
        assertEquals("status", node.path("arguments").path("fields").path(2).asText());

        setupConnection(HttpURLConnection.HTTP_OK, headers,
            "{\"arguments\": {}, \"result\": \"success\"}", null, "");

        manager.removeTorrent(new String[] {"foo"}, false);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-remove", node.path("method").asText());
        assertEquals("foo", node.path("arguments").path("ids").path(0).asText());
        assertNull(node.path("arguments").path("ids").get(1));
        assertFalse(node.path("arguments").path("delete-local-data").asBoolean());

        cursor = dataSource.getTorrentCursor(profile.getId(), null, new String[0], null, false);
        assertEquals(2, cursor.getCount());
        cursor.close();

        setupConnection(HttpURLConnection.HTTP_OK, headers,
            "{\"arguments\": {}, \"result\": \"error\"}", null, "");

        try {
            manager.removeTorrent(new String[]{"foo"}, false);
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals("error", e.getMessage());
        }

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        cursor = dataSource.getTorrentCursor(profile.getId(), null, new String[0], null, false);
        assertEquals(2, cursor.getCount());
        cursor.close();

        setupConnection(HttpURLConnection.HTTP_OK, headers,
            "{\"arguments\": {}, \"result\": \"success\"}", null, "");

        manager.removeTorrent(new String[] {"bar"}, true);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-remove", node.path("method").asText());
        assertEquals("bar", node.path("arguments").path("ids").path(0).asText());
        assertNull(node.path("arguments").path("ids").get(1));
        assertTrue(node.path("arguments").path("delete-local-data").asBoolean());

        cursor = dataSource.getTorrentCursor(profile.getId(), null, new String[0], null, false);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        assertEquals("mint 14", Torrent.getName(cursor));
        cursor.close();

        setupConnection(HttpURLConnection.HTTP_OK, headers,
            "{\"arguments\": {\"torrent-added\": {\"id\": 57, \"hashString\": \"beta\", \"name\": \"slackware 11\"}}, \"result\": \"success\"}", null, "");

        String hash = manager.addTorrent("magnet:somemagnet", null, "/foo", false);

        assertEquals("beta", hash);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-add", node.path("method").asText());
        assertEquals("magnet:somemagnet", node.path("arguments").path("filename").asText());
        assertNull(node.path("arguments").get("metainfo"));
        assertEquals("/foo", node.path("arguments").path("download-dir").asText());
        assertFalse(node.path("arguments").path("paused").asBoolean());

        cursor = dataSource.getTorrentCursor(profile.getId(), null, new String[0], null, false);
        assertEquals(2, cursor.getCount());
        cursor.moveToLast();
        assertEquals("slackware 11", Torrent.getName(cursor));
        cursor.close();

        setupConnection(HttpURLConnection.HTTP_OK, headers,
            "{\"arguments\": {\"torrent-duplicate\": {\"id\": 57, \"hashString\": \"beta\", \"name\": \"slackware 11\"}}, \"result\": \"duplicate torrent\"}", null, "");

        try {
            manager.addTorrent("magnet:somemagnet", null, "/foo", false);
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals("duplicate torrent", e.getMessage());
            assertEquals(-2, e.getCode());
        }

        setupConnection(HttpURLConnection.HTTP_OK, headers,
            "{\"arguments\": {\"torrent-added\": {\"id\": 59, \"hashString\": \"gamma\", \"name\": \"debian 6\"}}, \"result\": \"success\"}", null, "");

        hash = manager.addTorrent(null, "torrentfiledata", "/bar", true);

        assertEquals("gamma", hash);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-add", node.path("method").asText());
        assertNull(node.path("arguments").get("filename"));
        assertEquals("torrentfiledata", node.path("arguments").path("metainfo").asText());
        assertEquals("/bar", node.path("arguments").path("download-dir").asText());
        assertTrue(node.path("arguments").path("paused").asBoolean());

        cursor = dataSource.getTorrentCursor(profile.getId(), null, new String[0], null, false);
        assertEquals(3, cursor.getCount());
        cursor.moveToLast();
        assertEquals("debian 6", Torrent.getName(cursor));
        cursor.close();
    }

    @Test public void torrentProperties() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");

        manager.setTorrentAction(new String[] {"foo", "bar"}, "torrent-start");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-start", node.path("method").asText());
        assertEquals("foo", node.path("arguments").path("ids").path(0).asText());
        assertEquals("bar", node.path("arguments").path("ids").path(1).asText());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");

        manager.setTorrentLocation(new String[]{"foo", "bar"}, "/test/1", true);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set-location", node.path("method").asText());
        assertEquals("foo", node.path("arguments").path("ids").path(0).asText());
        assertEquals("bar", node.path("arguments").path("ids").path(1).asText());
        assertEquals("/test/1", node.path("arguments").path("location").asText());
        assertEquals(true, node.path("arguments").path("move").asBoolean());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentLocation(new String[] {"foo"}, "/test/2", false);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set-location", node.path("method").asText());
        assertEquals("foo", node.path("arguments").path("ids").path(0).asText());
        assertEquals("/test/2", node.path("arguments").path("location").asText());
        assertEquals(false, node.path("arguments").path("move").asBoolean());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        boolean thrown = false;
        try {
            manager.setTorrentProperty(new String[]{"foo", "bar"}, "test", null);
        } catch (IllegalArgumentException ignored) {
            thrown = true;
        }

        assertTrue("Setting an invalid torrent property should throw an error", thrown);

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");

        thrown = false;
        try {
            manager.setTorrentProperty(new String[]{"foo", "bar"}, Torrent.SetterFields.DOWNLOAD_LIMIT, "cast error");
        } catch (ClassCastException ignored) {
            thrown = true;
        }

        assertTrue("Properties expect certain value types", thrown);

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo", "bar"}, Torrent.SetterFields.DOWNLOAD_LIMIT, 10l);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals("foo", node.path("arguments").path("ids").path(0).asText());
        assertEquals("bar", node.path("arguments").path("ids").path(1).asText());
        assertEquals(10l, node.path("arguments").path(Torrent.SetterFields.DOWNLOAD_LIMIT).asLong());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.DOWNLOAD_LIMITED, true);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals("foo", node.path("arguments").path("ids").path(0).asText());
        assertEquals(true, node.path("arguments").path(Torrent.SetterFields.DOWNLOAD_LIMITED).asBoolean());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.PEER_LIMIT, 20);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(20, node.path("arguments").path(Torrent.SetterFields.PEER_LIMIT).asInt());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.QUEUE_POSITION, 30);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(30, node.path("arguments").path(Torrent.SetterFields.QUEUE_POSITION).asInt());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.SEED_RATIO_LIMIT, 1.5f);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(1.5, node.path("arguments").path(Torrent.SetterFields.SEED_RATIO_LIMIT).asDouble(), 0);

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.SEED_RATIO_MODE, Torrent.SeedRatioMode.NO_LIMIT);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(Torrent.SeedRatioMode.NO_LIMIT, node.path("arguments").path(Torrent.SetterFields.SEED_RATIO_MODE).asInt());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.SESSION_LIMITS, true);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(true, node.path("arguments").path(Torrent.SetterFields.SESSION_LIMITS).asBoolean());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.TORRENT_PRIORITY, 4);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(4, node.path("arguments").path(Torrent.SetterFields.TORRENT_PRIORITY).asInt());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.UPLOAD_LIMIT, 40l);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(40l, node.path("arguments").path(Torrent.SetterFields.UPLOAD_LIMIT).asLong());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.UPLOAD_LIMITED, true);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(true, node.path("arguments").path(Torrent.SetterFields.UPLOAD_LIMITED).asBoolean());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.FILES_WANTED, 1);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(1, node.path("arguments").path(Torrent.SetterFields.FILES_WANTED).path(0).asInt());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.FILES_WANTED, new Integer[] {2, 3});

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(2, node.path("arguments").path(Torrent.SetterFields.FILES_WANTED).path(0).asInt());
        assertEquals(3, node.path("arguments").path(Torrent.SetterFields.FILES_WANTED).path(1).asInt());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.FILES_UNWANTED, 4);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(4, node.path("arguments").path(Torrent.SetterFields.FILES_UNWANTED).path(0).asInt());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.FILES_UNWANTED, new Integer[] {5, 6});

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(5, node.path("arguments").path(Torrent.SetterFields.FILES_UNWANTED).path(0).asInt());
        assertEquals(6, node.path("arguments").path(Torrent.SetterFields.FILES_UNWANTED).path(1).asInt());

        ArrayList<Integer> list = new ArrayList<>();

        list.add(1);
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.FILES_LOW, list);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(1, node.path("arguments").path(Torrent.SetterFields.FILES_LOW).path(0).asInt());
        list.clear();

        list.add(2);
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.FILES_NORMAL, list);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(2, node.path("arguments").path(Torrent.SetterFields.FILES_NORMAL).path(0).asInt());
        list.clear();

        list.add(3);
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.FILES_HIGH, list);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(3, node.path("arguments").path(Torrent.SetterFields.FILES_HIGH).path(0).asInt());
        assertEquals(1, node.path("arguments").path(Torrent.SetterFields.FILES_HIGH).size());
        list.clear();

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.TRACKER_ADD, "test");

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals("test", node.path("arguments").path(Torrent.SetterFields.TRACKER_ADD).asText());

        list.add(5);
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.TRACKER_REMOVE, list);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(5, node.path("arguments").path(Torrent.SetterFields.TRACKER_REMOVE).path(0).asInt());
        assertEquals(1, node.path("arguments").path(Torrent.SetterFields.TRACKER_REMOVE).size());

        ArrayList<String> tuple = new ArrayList<>();

        tuple.add("1");
        tuple.add("bar");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\"}", null, "");
        manager.setTorrentProperty(new String[]{"foo"}, Torrent.SetterFields.TRACKER_REPLACE, tuple);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("torrent-set", node.path("method").asText());
        assertEquals(1, node.path("arguments").path(Torrent.SetterFields.TRACKER_REPLACE).path(0).asInt());
        assertEquals("bar", node.path("arguments").path(Torrent.SetterFields.TRACKER_REPLACE).path(1).asText());
        assertEquals(2, node.path("arguments").path(Torrent.SetterFields.TRACKER_REPLACE).size());
    }

    @Test public void misc() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\", \"arguments\": {\"port-is-open\": true}}", null, "");
        boolean isPortOpen = manager.testPort();

        assertTrue(isPortOpen);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(connection.outputStream.toString());

        assertEquals("port-test", node.path("method").asText());
        assertNull(node.get("arguments"));

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\", \"arguments\": {\"blocklist-size\": 1234567890}}", null, "");
        long blocklistSize = manager.updateBlocklist();

        assertEquals(1234567890l, blocklistSize);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("blocklist-update", node.path("method").asText());
        assertNull(node.get("arguments"));

        defaultPrefs.edit().putString(G.PREF_LIST_DIRECTORY, "/test").commit();

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\", \"arguments\": {\"path\": \"/test\", \"size-bytes\": 123123123}}", null, "");
        long freeSpace = manager.getFreeSpace(null);

        assertEquals(123123123l, freeSpace);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("free-space", node.path("method").asText());
        assertEquals("/test", node.path("arguments").path("path").asText());

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\", \"arguments\": {\"path\": \"/test\", \"size-bytes\": 123123123}}", null, "");
        freeSpace = manager.getFreeSpace("/default/path");

        assertEquals(123123123l, freeSpace);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("free-space", node.path("method").asText());
        assertEquals("/test", node.path("arguments").path("path").asText());

        defaultPrefs.edit().putString(G.PREF_LIST_DIRECTORY, null).commit();
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\", \"arguments\": {\"path\": \"/test2\", \"size-bytes\": 123123123123}}", null, "");
        freeSpace = manager.getFreeSpace("/test2");

        assertEquals(123123123123l, freeSpace);

        mapper = new ObjectMapper();
        node = mapper.readTree(connection.outputStream.toString());

        assertEquals("free-space", node.path("method").asText());
        assertEquals("/test2", node.path("arguments").path("path").asText());
    }

    private void setupConnection(int responseCode, Map<String, String> headerFields,
                                 String response, String contentEncoding, String responseMessage) {
        connection.responseCode = responseCode;
        connection.contentEncoding = contentEncoding;
        connection.responseMessage = responseMessage;

        InputStream is = new ByteArrayInputStream(Charset.forName("UTF-16").encode(response).array());
        connection.inputStream = is;

        if (headerFields != null) {
            connection.headerFields.putAll(headerFields);
        }

        connection.outputStream = new ByteArrayOutputStream();
    }

    private static class HttpsURLConnectionTest extends HttpsURLConnection {
        public int connectCount = 0;
        public boolean isConnected;
        public int readTimeout;
        public int connectTimeout;
        public Map<String, String> requestProperties = new HashMap<>();
        public boolean useCaches;
        public boolean allowUserInteraction;
        public String requestMethod = "GET";
        public boolean doInput;
        public boolean doOutput;

        public SSLSocketFactory socketFactory;
        public HostnameVerifier hostnameVerifier;

        public OutputStream outputStream;
        public InputStream inputStream;
        public int responseCode;
        public Map<String, String> headerFields = new HashMap<>();
        public String contentEncoding;
        public String responseMessage;

        public Runnable connectRunnable;

        public HttpsURLConnectionTest(URL url) {
            super(url);
        }

        public void setConnectTest(Runnable connectTest) {
            this.connectRunnable = connectTest;
        }

        @Override public String getCipherSuite() {
            return null;
        }

        @Override public Certificate[] getLocalCertificates() {
            return new Certificate[0];
        }

        @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
            return new Certificate[0];
        }

        @Override public void disconnect() { isConnected = false; }

        @Override public boolean usingProxy() { return false; }

        @Override public void connect() throws IOException {
            if (connectRunnable != null) {
                connectRunnable.run();
            }
            isConnected = true;
            connectCount++;
        }

        @Override public void setReadTimeout(int timeout) { readTimeout = timeout; }

        @Override public void setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        @Override public void setRequestProperty(String key, String value) {
            requestProperties.put(key, value);
        }

        @Override public void setUseCaches(boolean useCaches) {
            this.useCaches = useCaches;
        }

        @Override public void setAllowUserInteraction(boolean allowUserInteraction) {
            this.allowUserInteraction = allowUserInteraction;
        }

        @Override public void setRequestMethod(String requestMethod) {
            this.requestMethod = requestMethod;
        }

        @Override public void setDoInput(boolean doInput) {
            this.doInput = doInput;
        }

        @Override public void setDoOutput(boolean doOutput) {
            this.doOutput = doOutput;
        }

        @Override public void setSSLSocketFactory(SSLSocketFactory sf) {
            socketFactory = sf;
        }

        @Override public void setHostnameVerifier(HostnameVerifier hv) {
            hostnameVerifier = hv;
        }

        @Override public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override public int getResponseCode() {
            return responseCode;
        }

        @Override public InputStream getInputStream() {
            return inputStream;
        }

        @Override public String getHeaderField(String key) {
            return headerFields.get(key);
        }

        @Override public String getContentEncoding() {
            return contentEncoding;
        }

        @Override public String getResponseMessage() {
            return responseMessage;
        }
    }

    private static class DelayedHttpsURLConnectionTest extends HttpsURLConnectionTest {
        private int delay;

        public DelayedHttpsURLConnectionTest(URL url) {
            super(url);
        }

        public void setDelay(int delay) {
            this.delay = delay;
        }

        @Override public void connect() throws IOException {
            if (delay * 1000 > connectTimeout) {
                throw new SocketTimeoutException();
            }
            super.connect();
        }
    }
}
