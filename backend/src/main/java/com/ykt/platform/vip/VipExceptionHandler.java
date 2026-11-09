package com.ykt.platform.vip;

import com.ykt.platform.entity.VipOrder;
import com.ykt.platform.entity.VipSettings;
import com.ykt.platform.repository.VipOrderRepository;
import com.ykt.platform.repository.VipSettingsRepository;
import com.ykt.platform.service.VipService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@link VipRequiredException} 转成 HTTP 402 响应。
 * 响应体带价格信息，前端 axios 拦截器统一捕获 402 后跳转付费页，无需各入口单独处理。
 */
@RestControllerAdvice
public class VipExceptionHandler {

    private final VipSettingsRepository settingsRepository;
    private final VipOrderRepository orderRepository;
    private final VipService vipService;

    public VipExceptionHandler(VipSettingsRepository settingsRepository, VipOrderRepository orderRepository,
                               VipService vipService) {
        this.settingsRepository = settingsRepository;
        this.orderRepository = orderRepository;
        this.vipService = vipService;
    }

    @ExceptionHandler(VipRequiredException.class)
    public ResponseEntity<Map<String, Object>> handle(VipRequiredException ex) {
        VipSettings settings = settingsRepository.findAll().stream().findFirst().orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "VIP_REQUIRED");
        body.put("message", ex.getMessage());
        body.put("batch", ex.getBatch());
        body.put("datePassPriceFen", settings == null ? 0 : settings.getDatePassPriceFen());
        // 该批次若被管理员单独定价，返回批次价（前端付费页/提示展示真实应付）
        body.put("batchPriceFen", vipService.datePassPriceFor(ex.getBatch()));
        body.put("monthPriceFen", settings == null ? 0 : settings.getMonthPriceFen());
        body.put("monthDays", settings == null ? 30 : settings.getMonthDays());
        body.put("pendingOrders", orderRepository.countByStatus(VipOrder.STATUS_CREATED));
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
    }
}
