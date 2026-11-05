package com.ayush.armour;

import org.junit.Test;
import org.mockito.MockedStatic;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RemoteServiceTest {
    @Test public void realAdmissionPolicyPreservesPublicRoutesAndProtectsBodies() throws Exception {
        RemoteService service = mock(RemoteService.class, CALLS_REAL_METHODS);
        Method admit = RemoteService.class.getDeclaredMethod("admit", String.class, String.class, Map.class, long.class);
        admit.setAccessible(true);
        Map<String, String> headers = new HashMap<>();
        try (MockedStatic<Prefs> prefs = mockStatic(Prefs.class)) {
            prefs.when(() -> Prefs.tokenValid(service, "valid")).thenReturn(true);
            for (String path : Arrays.asList("/", "/?phone", "/manifest.json")) {
                RemoteServer.Decision d = (RemoteServer.Decision) admit.invoke(service, "GET", path, headers, 0L);
                assertNull(d.rejection); assertEquals(0, d.limit); assertFalse(d.upload);
            }
            for (String path : Arrays.asList("/api/pair", "/api/pair/start")) {
                RemoteServer.Decision d = (RemoteServer.Decision) admit.invoke(service, "POST", path, headers, 0L);
                assertNull(d.rejection); assertEquals(1024, d.limit);
            }
            for (String path : Arrays.asList("/api/throw", "/api/present", "/api/state")) {
                RemoteServer.Decision d = (RemoteServer.Decision) admit.invoke(service, "POST", path, headers, 8388608L);
                assertEquals(401, d.rejection.status);
            }
            headers.put("x-token", "valid");
            RemoteServer.Decision photo = (RemoteServer.Decision) admit.invoke(service, "POST", "/api/throw", headers, 1L);
            assertNull(photo.rejection); assertEquals(8388608, photo.limit); assertFalse(photo.upload);
            RemoteServer.Decision upload = (RemoteServer.Decision) admit.invoke(service, "POST", "/api/present", headers, 1L);
            assertNull(upload.rejection); assertEquals(1073741824L, upload.limit); assertTrue(upload.upload);
        }
    }
    @Test public void handlerRejectsInvalidTokenBeforeJsonParsing() throws Exception {
        RemoteService service = mock(RemoteService.class, CALLS_REAL_METHODS);
        Method handle = RemoteService.class.getDeclaredMethod("handle", String.class, String.class, Map.class, byte[].class);
        handle.setAccessible(true);
        try (MockedStatic<Prefs> prefs = mockStatic(Prefs.class)) {
            RemoteServer.Response response = (RemoteServer.Response) handle.invoke(service, "POST", "/api/throw", new HashMap<>(), new byte[]{'!'});
            assertEquals(401, response.status);
        }
    }
    @Test public void foregroundStartupUsesOnlySupportedOverloads() {
        for (int sdk : new int[]{26, 28, 29, 33, 34}) {
            RemoteService service = mock(RemoteService.class, CALLS_REAL_METHODS);
            android.app.Notification notification = mock(android.app.Notification.class);
            // Android's framework methods are supplied by the OS; mock their effects on the JVM.
            doNothing().when(service).startForeground(anyInt(), any(android.app.Notification.class));
            doNothing().when(service).startForeground(anyInt(), any(android.app.Notification.class), anyInt());
            service.startForegroundCompat(notification, sdk);
            if (sdk >= 34) {
                verify(service).startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                verify(service, never()).startForeground(anyInt(), any(android.app.Notification.class));
            } else {
                verify(service).startForeground(1, notification);
                verify(service, never()).startForeground(anyInt(), any(android.app.Notification.class), anyInt());
            }
        }
    }
}
