package com.limou.agent.controller;

import com.limou.agent.exception.BusinessException;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.mq.OrderStatusNotifier;
import com.limou.agent.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 订单状态 SSE 推送端点
 * <p>
 * 前端通过 EventSource 订阅此端点，接收订单状态变更的实时推送。
 * 使用示例：
 * <pre>
 *   const es = new EventSource('/api/sse/order/{userId}');
 *   es.addEventListener('order_cancelled', e => { ... });
 *   es.addEventListener('order_paid', e => { ... });
 * </pre>
 */
@RestController
@RequestMapping("/sse")
public class OrderStatusSSEController {

    @Resource
    private OrderStatusNotifier orderStatusNotifier;

    @Resource
    private UserService userService;

    /**
     * 订阅当前用户的订单状态变更（需校验 userId 与登录用户一致）
     */
    @GetMapping("/order/{userId}")
    public SseEmitter subscribe(@PathVariable Long userId, HttpServletRequest request) {
        Long loginUserId = userService.getLoginUser(request).getId();
        if (!loginUserId.equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权订阅他人订单事件");
        }
        return orderStatusNotifier.register(userId);
    }
}