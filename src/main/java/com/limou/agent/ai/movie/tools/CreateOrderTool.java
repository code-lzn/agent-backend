package com.limou.agent.ai.movie.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limou.agent.ai.movie.ConversationContext;
import com.limou.agent.ai.movie.MovieStateManager;
import com.limou.agent.mapper.*;
import com.limou.agent.model.dto.movie.ConversationState;
import com.limou.agent.model.entity.*;
import com.limou.agent.model.enums.OrderStatusEnum;
import com.limou.agent.model.enums.SeatStatusEnum;
import com.limou.agent.mq.OrderTimeoutConfig;
import com.limou.agent.mq.OrderTimeoutMessage;
import com.limou.agent.service.SystemConfigService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单创建工具
 * 基于已锁定的座位生成订单，设置 15 分钟支付超时
 */
@Slf4j
@Component
public class CreateOrderTool extends BaseTool {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderSeatMapper orderSeatMapper;

    @Resource
    private SeatMapper seatMapper;

    @Resource
    private ScheduleMapper scheduleMapper;

    @Resource
    private MovieStateManager stateManager;

    @Resource
    private FilmMapper filmMapper;

    @Resource
    private CinemaMapper cinemaMapper;

    @Resource
    private HallMapper hallMapper;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Resource
    private SystemConfigService systemConfigService;

    @Tool(description = "基于已锁定的座位创建订单。传入场次ID和已锁定的座位ID数组。返回订单确认JSON，含订单详情和15分钟倒计时")
    @Transactional(rollbackFor = Exception.class)
    public String createOrder(
            @ToolParam(description = "场次ID") Long scheduleId,
            @ToolParam(description = "已锁定座位ID数组") List<Long> seatIds,
            @ToolParam(description = "用户ID") Long userId
    ) {
        try {
            if (seatIds == null || seatIds.isEmpty()) {
                return "{\"success\":false,\"error\":\"未指定座位，请先选择座位\"}";
            }
            if (userId == null) {
                return "{\"success\":false,\"error\":\"用户未登录\"}";
            }

            // 1. 验证座位状态（确保都是 locked）
            List<Seat> seats = seatMapper.selectListByQuery(
                    QueryWrapper.create()
                            .eq(Seat::getScheduleId, scheduleId)
                            .in(Seat::getId, seatIds)
            );

            if (seats.size() != seatIds.size()) {
                return "{\"success\":false,\"error\":\"部分座位不存在或不属于当前场次，请重新选座\"}";
            }

            List<Seat> notLocked = seats.stream()
                    .filter(s -> !SeatStatusEnum.LOCKED.getValue().equals(s.getStatus()))
                    .collect(Collectors.toList());
            if (!notLocked.isEmpty()) {
                return "{\"success\":false,\"error\":\"部分座位状态异常，请重新选座\"}";
            }

            // 2. 查询关联数据
            Schedule schedule = scheduleMapper.selectOneById(scheduleId);
            if (schedule == null) {
                return "{\"success\":false,\"error\":\"场次不存在\"}";
            }

            Film film = filmMapper.selectOneById(schedule.getFilmId());
            Cinema cinema = cinemaMapper.selectOneById(schedule.getCinemaId());
            Hall hall = hallMapper.selectOneById(schedule.getHallId());

            // 3. 计算总价
            BigDecimal totalPrice = seats.stream()
                    .map(s -> {
                        BigDecimal price = "vip".equals(s.getZone())
                                ? (schedule.getVipPrice() != null ? schedule.getVipPrice() : schedule.getPrice())
                                : schedule.getPrice();
                        return price != null ? price : BigDecimal.ZERO;
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 4. 生成订单号
            String orderNo = "MOV" + System.currentTimeMillis()
                    + String.format("%04d", new Random().nextInt(10000));

            // 5. 创建订单
            LocalDateTime now = LocalDateTime.now();
            int lockDuration = getLockDuration();
            LocalDateTime expireAt = now.plusMinutes(lockDuration);

            Order order = Order.builder()
                    .orderNo(orderNo)
                    .userId(userId)
                    .scheduleId(scheduleId)
                    .filmName(film != null ? film.getName() : "")
                    .cinemaName(cinema != null ? cinema.getName() : "")
                    .scheduleTime((schedule.getShowDate() != null ? schedule.getShowDate().toString() : "")
                            + " " + (schedule.getStartTime() != null ? schedule.getStartTime().toString() : ""))
                    .hallName(hall != null ? hall.getName() : "")
                    .totalPrice(totalPrice)
                    .count(seatIds.size())
                    .status(OrderStatusEnum.PENDING.getValue())
                    .expireAt(expireAt)
                    .isDelete(false)
                    .build();

            orderMapper.insertSelective(order);

            // 6. 创建订单-座位关联
            List<String> seatLabels = new ArrayList<>();
            for (Seat seat : seats) {
                OrderSeat orderSeat = OrderSeat.builder()
                        .orderId(order.getId())
                        .seatId(seat.getId())
                        .seatLabel(seat.getSeatLabel())
                        .isUsed(false)
                        .build();
                orderSeatMapper.insertSelective(orderSeat);
                seatLabels.add(seat.getSeatLabel());
            }

            // 6.5 票不在下单时生成，支付成功后才生成（见 AlipayController.handlePaymentSuccess）

            // 7. 发送延时消息到 RabbitMQ：15 分钟后检查是否已支付
            try {
                OrderTimeoutMessage msg = OrderTimeoutMessage.builder()
                        .orderId(order.getId())
                        .orderNo(orderNo)
                        .userId(userId)
                        .scheduleId(scheduleId)
                        .createdAt(System.currentTimeMillis())
                        .build();
                rabbitTemplate.convertAndSend(
                        OrderTimeoutConfig.ORDER_TIMEOUT_EXCHANGE,
                        OrderTimeoutConfig.ORDER_TIMEOUT_ROUTING_KEY,
                        msg);
                log.info("订单 {} 超时延时消息已发送", orderNo);
            } catch (Exception e) {
                log.error("发送订单超时延时消息失败: orderNo={}", orderNo, e);
                // 消息发送失败不影响订单创建，兜底由定时任务 OrderTimeoutScheduler 处理
            }

            // 8. 构建返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("orderId", order.getId().toString());
            result.put("orderNo", orderNo);
            result.put("filmName", order.getFilmName());
            result.put("cinemaName", order.getCinemaName());
            result.put("hallName", order.getHallName());
            result.put("scheduleTime", order.getScheduleTime());
            result.put("seatLabels", seatLabels);
            result.put("count", seatIds.size());
            result.put("totalPrice", totalPrice);
            result.put("expireAt", expireAt.toString());
            result.put("remainingMinutes", lockDuration);
            result.put("message", "为您确认：" + order.getFilmName() + "×" + seatIds.size()
                    + "张，" + String.join("、", seatLabels)
                    + "，共 ¥" + totalPrice + "。没问题就下单啦～");

            // ReAct 模式下将 orderId 写回 ConversationState（Graph 模式由 CreateOrderNode 处理）
            String convId = ConversationContext.get();
            if (convId != null) {
                try {
                    ConversationState convState = stateManager.getState(convId);
                    convState.setOrderId(order.getId());
                    stateManager.saveState(convId, convState);
                    log.info("createOrder 写回 orderId={} 到 Redis: conversationId={}", order.getId(), convId);
                } catch (Exception e) {
                    log.warn("createOrder 写回状态失败: conversationId={}", convId, e);
                }
            }

            log.info("createOrder 成功: orderNo={}, film={}, seats={}, totalPrice={}",
                    orderNo, film != null ? film.getName() : "", seatLabels, totalPrice);
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("createOrder 失败", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @Override
    public String getToolName() {
        return "createOrder";
    }

    @Override
    public String getDisplayName() {
        return "创建订单";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        Long scheduleId = arguments.getLong("scheduleId");
        return String.format("[工具调用] 创建订单 scheduleId=%d", scheduleId);
    }

    private int getLockDuration() {
        return readIntConfig("lockDuration", 15);
    }

    private int readIntConfig(String configKey, int defaultValue) {
        try {
            SystemConfig config = systemConfigService.getOne(
                    QueryWrapper.create().eq("configKey", configKey));
            if (config == null || StrUtil.isBlank(config.getConfigValue())) {
                return defaultValue;
            }
            String v = config.getConfigValue().trim().replaceAll("[\"']", "");
            return Integer.parseInt(v);
        } catch (Exception e) {
            log.warn("读取配置 {} 失败，使用默认 {}", configKey, defaultValue, e);
            return defaultValue;
        }
    }
}
