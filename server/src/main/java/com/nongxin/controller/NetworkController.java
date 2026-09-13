package com.nongxin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网地址：给"手机访问"用。
 *
 * <p>只返回本机非回环的 IPv4（私网地址），用于在界面上生成二维码。
 * 二维码里只有 {@code http://<内网IP>:<端口>/}，**不含任何密钥、聊天内容或凭据**（路线图 P4 的要求）。
 * 手机必须与电脑在同一个 Wi-Fi 下；本项目目前还没有账号体系，所以只适合自家网络或手机热点。
 */
@RestController
@RequestMapping("/api/network")
public class NetworkController {

    /** 把网卡名换成人话：无线网卡 / 有线网卡 / 虚拟网卡 / 异地组网。 */
    private static String friendlyName(String name, boolean virtual) {
        if (name.contains("tailscale") || name.contains("zerotier") || name.contains("pgy") || name.contains("oray")) return "异地组网";
        if (name.contains("wireless") || name.contains("wlan") || name.contains("wi-fi") || name.contains("wifi")) return "无线网卡";
        if (name.contains("ethernet") || name.contains("以太网")) return "有线网卡";
        if (virtual) return "虚拟网卡";
        return "网络地址";
    }

    /** 排序权重：远程可用优先，其次真实网卡，最后虚拟网卡。 */
    private static int rank(Map<String, Object> item) {
        if (Boolean.TRUE.equals(item.get("remote"))) return 0;
        return Boolean.TRUE.equals(item.get("virtual")) ? 2 : 1;
    }

    @GetMapping
    public Map<String, Object> addresses() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (NetworkInterface nic : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress address : java.util.Collections.list(nic.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("address", address.getHostAddress());
                    item.put("interface", nic.getName());
                    String name = (nic.getName() + " " + nic.getDisplayName()).toLowerCase();
                    // 异地组网（蒲公英 / Tailscale / ZeroTier 等）的地址是"农户在家、人在田里"唯一可用的路径，
                    // 不能被当成普通虚拟网卡置灰——单独标出来，界面会给它单独的二维码。
                    boolean remote = name.contains("tailscale") || name.contains("zerotier") || name.contains("tailnet")
                            || name.contains("pgy") || name.contains("oray") || name.contains("蒲公英") || name.contains("peanut");
                    // 其它虚拟网卡（代理 / TUN / VPN）手机连不上，必须标出来，否则用户会扫到错的二维码
                    boolean virtual = !remote && (nic.isVirtual() || nic.isPointToPoint()
                            || address.getHostAddress().startsWith("198.18.") || address.getHostAddress().startsWith("198.19."));
                    item.put("remote", remote);
                    if (remote) item.put("interface", "异地组网（远程访问）");
                    item.put("virtual", virtual);
                    // 网卡名对农民没有意义（wireless_32768 / iftype53_32768），换成能看懂的说法
                    item.put("label", friendlyName(name, virtual));
                    item.put("private", address.isSiteLocalAddress());
                    list.add(item);
                }
            }
        } catch (Exception ignored) {
            // 枚举网卡失败不影响其它功能，界面会退回"手动查 ipconfig"
        }
        // 排序：远程可用（Tailscale）→ 局域网真实网卡 → 虚拟网卡；并标出各自推荐的用途
        list.sort((a, b) -> {
            int rank = Integer.compare(rank(a), rank(b));
            return rank != 0 ? rank : String.valueOf(a.get("address")).compareTo(String.valueOf(b.get("address")));
        });
        boolean markedLan = false;
        for (Map<String, Object> item : list) {
            boolean remote = Boolean.TRUE.equals(item.get("remote"));
            boolean usable = !Boolean.TRUE.equals(item.get("virtual"));
            item.put("recommended", usable && !remote && !markedLan);
            if (usable && !remote) markedLan = true;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("addresses", list);
        result.put("hint", list.isEmpty()
                ? "没有检测到局域网地址：请用 ipconfig 查看本机 IPv4，并确认手机与电脑在同一 Wi-Fi。"
                : "同一 Wi-Fi 下用「局域网」地址；人在外面（4G）时用「远程访问」地址——手机也要装 Tailscale 并登录同一账号。标着「虚拟网卡」的地址（代理 / VPN / TUN）手机连不上。");
        return result;
    }
}
