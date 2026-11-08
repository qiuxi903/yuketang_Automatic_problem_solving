package com.ykt.platform.controller;

import com.ykt.platform.dto.VipOrderCreateRequest;
import com.ykt.platform.dto.VipOrderView;
import com.ykt.platform.dto.VipProductsResponse;
import com.ykt.platform.dto.VipQuotaUnlockRequest;
import com.ykt.platform.dto.VipRedeemRequest;
import com.ykt.platform.dto.VipStatusResponse;
import com.ykt.platform.security.SecurityUtil;
import com.ykt.platform.service.VipService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** VIP 用户侧接口（登录用户）。 */
@RestController
@RequestMapping("/api/vip")
public class VipController {

    private final VipService vipService;

    public VipController(VipService vipService) {
        this.vipService = vipService;
    }

    /** 我的权益总览：月卡到期时间、免费额度、已解锁批次、绑定账号。 */
    @GetMapping("/status")
    public VipStatusResponse status() {
        return vipService.status(SecurityUtil.currentUserId());
    }

    /** 付费页商品数据：价格、生效活动、各批次解锁态、收款方式。 */
    @GetMapping("/products")
    public VipProductsResponse products() {
        return vipService.products(SecurityUtil.currentUserId());
    }

    /** 收款码图片（base64 dataURL）：{wechat: ..., alipay: ...}，未上传的为 null。 */
    @GetMapping("/pay-qr")
    public Map<String, String> payQr() {
        return vipService.payQrImages();
    }

    /** 创建订单（DATE_PASS 需 targetBatch；可带折扣码）。 */
    @PostMapping("/orders")
    public VipOrderView createOrder(@RequestBody VipOrderCreateRequest request) {
        return vipService.createOrder(SecurityUtil.currentUserId(), request);
    }

    /** 我的订单列表。 */
    @GetMapping("/orders")
    public List<VipOrderView> myOrders() {
        return vipService.myOrders(SecurityUtil.currentUserId());
    }

    /** 用户标记「我已完成支付」（人工/监听渠道的提示标记，不改变订单状态）。 */
    @PostMapping("/orders/{orderNo}/claim-paid")
    public Map<String, Boolean> claimPaid(@PathVariable String orderNo) {
        vipService.claimPaid(SecurityUtil.currentUserId(), orderNo);
        return Map.of("ok", true);
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public Map<String, Boolean> cancelOrder(@PathVariable String orderNo) {
        vipService.cancel(SecurityUtil.currentUserId(), orderNo);
        return Map.of("ok", true);
    }

    /** 兑换码核销（DATE_PASS / MONTH_PASS / QUOTA）。 */
    @PostMapping("/redeem")
    public Map<String, Object> redeem(@RequestBody VipRedeemRequest request) {
        String message = vipService.redeem(SecurityUtil.currentUserId(), request);
        return Map.of("ok", true, "message", message);
    }

    /** 消耗 1 次免费额度解锁指定批次。 */
    @PostMapping("/unlock-quota")
    public Map<String, Boolean> unlockQuota(@RequestBody VipQuotaUnlockRequest request) {
        vipService.unlockWithQuota(SecurityUtil.currentUserId(), request);
        return Map.of("ok", true);
    }
}
