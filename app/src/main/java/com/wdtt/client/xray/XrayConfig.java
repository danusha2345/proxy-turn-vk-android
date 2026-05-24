package com.wdtt.client.xray;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Генерация Xray-конфига для режима VLESS-через-ВК.
 *
 * Идея (как VK_TURN_TCP в WINGSV): берём VLESS-outbound из vless:// ссылки, но
 * адрес/порт переписываем на локальный vk-turn relay (libvkturn.so -vless,
 * 127.0.0.1:RELAY). Xray делает VLESS+REALITY-хендшейк (с оригинальным SNI) поверх
 * TCP-соединения к relay, а relay пробрасывает байты через звонок ВК до сервера.
 *
 * Системный трафик заходит через TUN-inbound (fd от VpnService).
 */
public final class XrayConfig {

    private XrayConfig() {}

    /**
     * @param vlessLink   исходная vless:// ссылка (с REALITY-параметрами)
     * @param relayHost   локальный адрес vk-turn relay (обычно 127.0.0.1)
     * @param relayPort   локальный порт relay
     * @param mtu         MTU TUN-интерфейса
     */
    public static String buildVlessViaRelay(String vlessLink, String relayHost, int relayPort, int mtu)
        throws Exception {
        // 1. vless:// → Xray outbound (libXray парсит REALITY сам)
        String outboundJson = XrayBridge.convertShareLinkToOutboundJson(vlessLink);
        JSONObject proxyOutbound = new JSONObject(outboundJson);
        proxyOutbound.put("tag", "proxy");
        rewriteEndpoint(proxyOutbound, relayHost, relayPort);

        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("loglevel", "warning"));

        // TUN-inbound: системный трафик от VpnService (tunFd передаётся отдельно в runFromJson)
        JSONObject tun = new JSONObject();
        tun.put("tag", "tun-in");
        tun.put("protocol", "tun");
        tun.put("port", 0);
        tun.put("settings", new JSONObject().put("MTU", mtu).put("user_level", 0));
        tun.put("sniffing", buildSniffing());
        root.put("inbounds", new JSONArray().put(tun));

        // outbounds: proxy (VLESS через relay) + direct + block
        JSONArray outbounds = new JSONArray();
        outbounds.put(proxyOutbound);
        outbounds.put(new JSONObject().put("tag", "direct").put("protocol", "freedom")
            .put("settings", new JSONObject().put("domainStrategy", "UseIP")));
        outbounds.put(new JSONObject().put("tag", "block").put("protocol", "blackhole"));
        root.put("outbounds", outbounds);

        // routing: relay-адрес и приватные сети — direct (чтобы соединение relay не зациклилось в TUN);
        // остальное — в proxy.
        JSONObject routing = new JSONObject();
        routing.put("domainStrategy", "AsIs");
        JSONArray rules = new JSONArray();
        rules.put(new JSONObject()
            .put("type", "field")
            .put("ip", new JSONArray().put(relayHost).put("127.0.0.1/8").put("::1/128"))
            .put("outboundTag", "direct"));
        rules.put(new JSONObject()
            .put("type", "field")
            .put("network", "tcp,udp")
            .put("outboundTag", "proxy"));
        routing.put("rules", rules);
        root.put("routing", routing);

        return root.toString();
    }

    private static JSONObject buildSniffing() throws Exception {
        JSONObject sniffing = new JSONObject();
        sniffing.put("enabled", true);
        sniffing.put("destOverride", new JSONArray().put("http").put("tls").put("quic"));
        sniffing.put("routeOnly", false);
        return sniffing;
    }

    /** Переписать адрес/порт VLESS-outbound на локальный relay (vnext[0]). */
    private static void rewriteEndpoint(JSONObject outbound, String host, int port) throws Exception {
        JSONObject settings = outbound.optJSONObject("settings");
        if (settings == null) {
            return;
        }
        JSONArray vnext = settings.optJSONArray("vnext");
        if (vnext != null && vnext.length() > 0) {
            JSONObject server = vnext.optJSONObject(0);
            if (server != null) {
                server.put("address", host);
                server.put("port", port);
            }
        }
    }
}
