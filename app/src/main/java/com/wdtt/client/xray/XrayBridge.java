package com.wdtt.client.xray;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import libXray.DialerController;
import libXray.LibXray;

import org.json.JSONObject;

/**
 * Тонкий мост к встроенному Xray-core (libXray.aar, gomobile).
 * Адаптировано из WINGSV (GPL-3.0), упрощено для WDTT: только то, что нужно для
 * VLESS+REALITY через ВК — конвертация vless://, запуск из JSON с TUN fd, стоп,
 * защита сокетов через VpnService.protect (чтобы трафик Xray не зацикливался в TUN).
 */
public final class XrayBridge {

    /** Реализуется VpnService-ом: protect(fd) на исходящих сокетах Xray. */
    public interface SocketProtector {
        boolean protect(int fd);
    }

    private static final AtomicBoolean LOADED = new AtomicBoolean();
    private static final AtomicBoolean RUNTIME_STARTED = new AtomicBoolean();
    private static final AtomicBoolean CONTROLLERS_REGISTERED = new AtomicBoolean();
    private static final Object JNI_LOCK = new Object();

    private static final AtomicReference<SocketProtector> ACTIVE_PROTECTOR = new AtomicReference<>(null);

    // libXray зовёт это для каждого исходящего сокета — делегируем в активный VpnService.
    private static final DialerController DELEGATING_CONTROLLER = new DialerController() {
        @Override
        public boolean protectFd(long fd) {
            SocketProtector protector = ACTIVE_PROTECTOR.get();
            if (protector == null) {
                return true; // нет VpnService (не должно случаться в рабочем режиме)
            }
            try {
                return protector.protect((int) fd);
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    };

    private XrayBridge() {}

    /** vless://... → JSON outbound-объект Xray (libXray сам парсит REALITY-параметры). */
    public static String convertShareLinkToOutboundJson(String rawLink) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            String request = Base64.encodeToString(rawLink.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            JSONObject response = decodeResponse(LibXray.convertShareLinksToXrayJson(request));
            Object data = response.opt("data");
            if (data instanceof JSONObject) {
                return data.toString();
            }
            if (data != null) {
                return String.valueOf(data);
            }
            throw new IllegalStateException("libXray вернул пустой outbound config");
        }
    }

    /** Запуск Xray из полного JSON-конфига; tunFd — дескриптор TUN от VpnService. */
    public static void runFromJson(Context context, String configJson, int tunFd, SocketProtector protector)
        throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            ACTIVE_PROTECTOR.set(protector);
            ensureControllersRegisteredLocked();
            LibXray.resetDns();
            RUNTIME_STARTED.set(false);
            File datDir = ensureDatDir(context);
            String request = LibXray.newXrayRunFromJSONRequest(datDir.getAbsolutePath(), "", configJson, tunFd);
            decodeResponse(LibXray.runXrayFromJSON(request));
            RUNTIME_STARTED.set(true);
        }
    }

    public static void stop() {
        if (!LOADED.get()) {
            return;
        }
        synchronized (JNI_LOCK) {
            try {
                if (RUNTIME_STARTED.get()) {
                    LibXray.stopXray();
                }
            } catch (Throwable ignored) {
                // best-effort
            } finally {
                RUNTIME_STARTED.set(false);
                ACTIVE_PROTECTOR.set(null);
                try {
                    LibXray.resetDns();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static boolean isRunning() {
        return LOADED.get() && RUNTIME_STARTED.get();
    }

    private static void ensureLoaded() {
        if (LOADED.compareAndSet(false, true)) {
            LibXray.touch();
        }
    }

    private static void ensureControllersRegisteredLocked() {
        if (CONTROLLERS_REGISTERED.compareAndSet(false, true)) {
            LibXray.registerDialerController(DELEGATING_CONTROLLER);
            LibXray.registerListenerController(DELEGATING_CONTROLLER);
        }
    }

    private static File ensureDatDir(Context context) {
        File datDir = new File(context.getFilesDir(), "xray/geo");
        if (!datDir.exists()) {
            datDir.mkdirs();
        }
        return datDir;
    }

    private static JSONObject decodeResponse(String base64Response) throws Exception {
        byte[] decoded = Base64.decode(base64Response, Base64.DEFAULT);
        JSONObject response = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        if (!response.optBoolean("success", false)) {
            throw new IllegalStateException(response.optString("error", "libXray request failed"));
        }
        return response;
    }
}
