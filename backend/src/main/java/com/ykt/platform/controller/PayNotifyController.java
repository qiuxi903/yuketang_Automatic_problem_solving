package com.ykt.platform.controller;

import com.ykt.platform.config.VipProperties;
import com.ykt.platform.dto.PayNotifyRequest;
import com.ykt.platform.service.VipAdminService;
import com.ykt.platform.service.VipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 支付回调（免签监听 / 虎皮椒）。permitAll（见 SecurityConfig），鉴权靠令牌/验签：
 * 监听回调必须携带 listenToken（后台「活动与支付设置」页可查），令牌错误一律 403。
 */
@RestController
public class PayNotifyController {

    private static final Logger log = LoggerFactory.getLogger(PayNotifyController.class);

    private final VipService vipService;
    private final VipAdminService adminService;
    private final VipProperties properties;

    public PayNotifyController(VipService vipService, VipAdminService adminService, VipProperties properties) {
        this.vipService = vipService;
        this.adminService = adminService;
        this.properties = properties;
    }

    /**
     * 免签监听回调：pay-listener（电脑端监听工具）POST {channel, amountFen, raw}。
     * 令牌可用 header X-Pay-Token 或 query ?token= 携带。
     */
    @PostMapping("/api/pay/notify")
    public Map<String, Object> notify(@RequestBody PayNotifyRequest request,
                                      @RequestHeader(value = "X-Pay-Token", required = false) String headerToken,
                                      @RequestParam(value = "token", required = false) String queryToken) {
        String token = StringUtils.hasText(headerToken) ? headerToken : queryToken;
        if (!adminService.listenTokenValid(token)) {
            log.warn("监听回调令牌无效，已拒绝");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "监听令牌无效");
        }
        if (request.amountFen() == null || request.amountFen() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountFen 无效");
        }
        boolean matched = vipService.payNotify(request.channel(), request.amountFen(), request.raw());
        return Map.of("matched", matched);
    }

    /**
     * 虎皮椒回调（骨架）：按官方文档异步通知参数验签（appid + hash）后核销。
     * NOT RUN：当前无商户凭据，配置 ykt.vip.xunhu-appid/secret 并开通后需实测联调。
     */
    @PostMapping("/api/pay/notify/xunhu")
    public Map<String, Object> xunhuNotify(@RequestParam Map<String, String> params) {
        String appid = properties.xunhuAppid();
        String secret = properties.xunhuSecret();
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "虎皮椒渠道未配置凭据");
        }
        String hash = params.get("hash");
        String receivedAppid = params.get("appid");
        if (hash == null || !appid.equals(receivedAppid) || !verifyXunhuHash(params, secret)) {
            log.warn("虎皮椒回调验签失败：{}", params.keySet());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "回调验签失败");
        }
        // 虎皮椒金额单位为元（字符串，两位小数），transaction_id 为平台流水
        String money = params.getOrDefault("money", "0");
        int amountFen = new java.math.BigDecimal(money).movePointRight(2).intValue();
        boolean matched = vipService.payNotify("xunhu", amountFen, params.get("transaction_id"));
        return Map.of("matched", matched);
    }

    /**
     * 虎皮椒验签规则：按 key 名 ASCII 升序拼非空参数 k=v&…（不含 hash 与空值），
     * 尾拼 &secret= 后整体 MD5 小写。
     */
    private static boolean verifyXunhuHash(Map<String, String> params, String secret) {
        StringBuilder sb = new StringBuilder();
        params.keySet().stream()
                .filter(k -> !"hash".equals(k))
                .sorted()
                .forEach(k -> {
                    String v = params.get(k);
                    if (v != null && !v.isEmpty()) {
                        sb.append(k).append('=').append(v).append('&');
                    }
                });
        sb.append("secret=").append(secret);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equalsIgnoreCase(params.get("hash"));
        } catch (java.security.NoSuchAlgorithmException e) {
            return false;
        }
    }
}
