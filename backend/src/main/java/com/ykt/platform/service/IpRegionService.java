package com.ykt.platform.service;

import jakarta.annotation.PreDestroy;
import org.lionsoul.ip2region.xdb.Searcher;
import org.lionsoul.ip2region.xdb.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * IP 离线归属地（ip2region v4 xdb，数据精确到市/运营商）。
 * xdb 放 backend/data/ip2region_v4.xdb（数据目录，gitignored）；
 * 文件缺失时降级为「未知」，不影响登录。
 *
 * 说明：IP 定位技术上限为城市/区县级，「街区级」定位需要浏览器 GPS 授权，与本库无关。
 */
@Service
public class IpRegionService {

    private static final Logger log = LoggerFactory.getLogger(IpRegionService.class);

    private final Path xdbPath;
    private Searcher searcher;
    private boolean available;

    public IpRegionService(@Value("${ykt.ip-region.xdb:./data/ip2region_v4.xdb}") String xdbPath) {
        this.xdbPath = Paths.get(xdbPath);
        try {
            if (!Files.exists(this.xdbPath)) {
                log.warn("IP 归属地库缺失：{}（归属地将显示「未知」）。请下载 ip2region_v4.xdb 放入 data/ 目录", this.xdbPath);
                return;
            }
            // 全量载入内存（约 11MB），查询零 IO、线程安全
            this.searcher = Searcher.newWithFileOnly(Version.IPv4, this.xdbPath.toString());
            this.available = true;
            log.info("IP 归属地库已加载：{}", this.xdbPath);
        } catch (Exception e) {
            log.error("IP 归属地库加载失败（将显示「未知」）：{}", e.getMessage());
        }
    }

    /**
     * 查询归属地，格式化为「省份 城市 · 运营商」；内网/解析失败返回友好文案。
     */
    public synchronized String locate(String ip) {
        if (ip == null || ip.isBlank()) {
            return "未知";
        }
        if (isPrivateOrLoopback(ip)) {
            return "局域网";
        }
        if (!available) {
            return "未知";
        }
        try {
            // v4 返回格式：国家|区域|省份|城市|ISP，如「中国|0|浙江省|杭州市|电信」
            String raw = searcher.search(ip);
            if (raw == null || raw.isBlank()) {
                return "未知";
            }
            String[] parts = raw.split("\\|");
            String country = pick(parts, 0);
            String province = pick(parts, 2);
            String city = pick(parts, 3);
            String isp = pick(parts, 4);
            String where;
            if ("0".equals(province) || province.isBlank()) {
                where = country.isBlank() || "0".equals(country) ? "未知" : country;
            } else {
                where = province + (city.isBlank() || city.equals(province) ? "" : " " + city);
            }
            String result = where + (isp.isBlank() || "0".equals(isp) ? "" : " · " + isp);
            return result.isBlank() ? "未知" : result;
        } catch (Exception e) {
            log.debug("IP 归属地查询失败 {}: {}", ip, e.getMessage());
            return "未知";
        }
    }

    private static String pick(String[] parts, int idx) {
        if (parts == null || idx >= parts.length) {
            return "";
        }
        String v = parts[idx];
        return v == null || "0".equals(v) ? "" : v.trim();
    }

    /** 内网/回环地址不做归属地查询。 */
    private static boolean isPrivateOrLoopback(String ip) {
        // IPv6 回环有「::1」与全写「0:0:0:0:0:0:0:1」两种形式
        if (ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        return ip.equals("127.0.0.1") || ip.startsWith("192.168.")
                || ip.startsWith("10.") || ip.startsWith("172.16.") || ip.startsWith("172.17.")
                || ip.startsWith("172.18.") || ip.startsWith("172.19.") || ip.startsWith("172.20.")
                || ip.startsWith("172.21.") || ip.startsWith("172.22.") || ip.startsWith("172.23.")
                || ip.startsWith("172.24.") || ip.startsWith("172.25.") || ip.startsWith("172.26.")
                || ip.startsWith("172.27.") || ip.startsWith("172.28.") || ip.startsWith("172.29.")
                || ip.startsWith("172.30.") || ip.startsWith("172.31.") || ip.startsWith("169.254.")
                || ip.equals("unknown");
    }

    @PreDestroy
    public void close() {
        if (searcher != null) {
            try {
                searcher.close();
            } catch (IOException ignored) {
            }
        }
    }
}
