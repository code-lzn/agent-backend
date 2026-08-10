package com.limou.agent.controller;

import com.limou.agent.config.AlipayConfig;
import com.limou.agent.model.entity.Order;
import com.limou.agent.model.enums.OrderStatusEnum;
import com.limou.agent.service.AlipayService;
import com.limou.agent.service.OrderService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝沙箱回调。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/payment/alipay")
@Slf4j
public class AlipayController {

    @Autowired
    private AlipayService alipayService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AlipayConfig alipayConfig;

    @GetMapping(value = "/pay", produces = "text/html;charset=UTF-8")
    public String payPage(@RequestParam Long orderId) {
        Order order = orderService.getById(orderId);
        if (order == null || !OrderStatusEnum.PENDING.getValue().equals(order.getStatus())) {
            return "<h1>订单不存在或状态异常</h1>";
        }
        String subject = order.getFilmName() + " - 电影票";
        String totalAmount = order.getTotalPrice().toString();
        return alipayService.createPayPage(order.getOrderNo(), totalAmount, subject);
    }

    /**
     * 支付宝沙箱同步返回。
     * 用户支付完成后支付宝跳转到 return_url。
     * 同步回调直接更新订单状态（不等异步通知），然后重定向到前端影票详情页。
     */
    @GetMapping("/return")
    public String returnPage(@RequestParam String out_trade_no,
            @RequestParam(required = false) String trade_no,
            @RequestParam(required = false) String total_amount) {
        try {
            QueryWrapper qw = QueryWrapper.create().eq("orderNo", out_trade_no);
            Order order = orderService.getOne(qw);
            if (order == null) {
                return "<script>window.location.replace('" + alipayConfig.getFrontendUrl() + "');</script>";
            }

            String status = order.getStatus();
            // 仅待支付订单需处理支付成功；已支付（异步通知已处理）直接跳影票页；
            // 已取消/已退款/已完成等一律不复活，跳回首页。
            if (OrderStatusEnum.PENDING.getValue().equals(status)) {
                orderService.handlePaymentSuccess(order, trade_no);
            } else if (!OrderStatusEnum.PAID.getValue().equals(status)) {
                log.warn("同步回调跳过非待支付订单: orderNo={}, status={}", out_trade_no, status);
                return "<script>window.location.replace('" + alipayConfig.getFrontendUrl() + "');</script>";
            }

            return "<script>window.location.replace('" + alipayConfig.getFrontendUrl() + "/payment-success/" + order.getId() + "');</script>";
        } catch (Exception e) {
            // 事务已在 OrderService.handlePaymentSuccess 内回滚，这里只需返回失败跳转
            log.error("同步回调处理异常: out_trade_no={}", out_trade_no, e);
            return "<script>window.location.replace('" + alipayConfig.getFrontendUrl() + "');</script>";
        }
    }

    /**
     * 支付宝沙箱异步通知。
     * 支付宝在用户支付完成后，会向 notifyUrl 发送 POST 请求。
     */
    @PostMapping("/notify")
    public String notify(HttpServletRequest request) {
        try {
            Map<String, String> params = new HashMap<>();
            Map<String, String[]> requestParams = request.getParameterMap();
            for (Map.Entry<String, String[]> entry : requestParams.entrySet()) {
                String name = entry.getKey();
                String[] values = entry.getValue();
                String value = String.join(",", values);
                params.put(name, value);
            }
            log.info("收到支付宝异步通知: {}", params);

            boolean verifyResult = alipayService.verifyNotify(params);
            if (!verifyResult) {
                log.warn("支付宝通知签名验证失败");
                return "failure";
            }

            String tradeStatus = params.get("trade_status");
            String outTradeNo = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");

            if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                QueryWrapper qw = QueryWrapper.create().eq("orderNo", outTradeNo);
                Order order = orderService.getOne(qw);
                if (order == null) {
                    log.warn("订单不存在: {}", outTradeNo);
                    return "failure";
                }

                String status = order.getStatus();
                // 仅待支付订单需处理支付成功；已支付忽略重复通知；
                // 已取消/已退款/已完成等一律不复活，返回 success 停止支付宝重试。
                if (!OrderStatusEnum.PENDING.getValue().equals(status)) {
                    log.warn("异步通知跳过非待支付订单: orderNo={}, status={}", outTradeNo, status);
                    return "success";
                }

                orderService.handlePaymentSuccess(order, tradeNo);
                log.info("支付宝异步通知处理成功，订单号: {}, 交易号: {}", outTradeNo, tradeNo);
            }

            return "success";
        } catch (Exception e) {
            // 事务已在 OrderService.handlePaymentSuccess 内回滚，返回 failure 让支付宝重试
            log.error("支付宝异步通知处理异常", e);
            return "failure";
        }
    }
}
