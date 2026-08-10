package com.limou.agent.ai.movie.tools;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limou.agent.ai.movie.ConversationContext;
import com.limou.agent.ai.movie.MovieStateManager;
import com.limou.agent.mapper.HallMapper;
import com.limou.agent.mapper.OrderMapper;
import com.limou.agent.mapper.OrderSeatMapper;
import com.limou.agent.model.dto.movie.ConversationState;
import com.limou.agent.mapper.ScheduleMapper;
import com.limou.agent.mapper.SeatMapper;
import com.limou.agent.model.entity.Hall;
import com.limou.agent.model.entity.Order;
import com.limou.agent.model.entity.OrderSeat;
import com.limou.agent.model.entity.Schedule;
import com.limou.agent.model.entity.Seat;
import com.limou.agent.model.enums.SeatStatusEnum;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 座位图工具
 * 获取指定场次的完整座位图（二维结构），含每个座位的状态和价格
 */
@Slf4j
@Component
public class GetSeatMapTool extends BaseTool {

    @Resource
    private SeatMapper seatMapper;

    @Resource
    private ScheduleMapper scheduleMapper;

    @Resource
    private HallMapper hallMapper;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderSeatMapper orderSeatMapper;

    @Resource
    private MovieStateManager stateManager;

    @Tool(description = "获取指定场次的座位图，返回二维座位矩阵JSON。每个座位包含 seatId、行列号、标签、区域(vip/regular)、状态(available/locked/sold)、价格")
    public String getSeatMap(
            @ToolParam(description = "场次ID") Long scheduleId
    ) {
        try {
            // 1. 查询场次和影厅信息
            Schedule schedule = scheduleMapper.selectOneById(scheduleId);
            if (schedule == null) {
                return "{\"error\":\"场次不存在\"}";
            }

            Hall hall = hallMapper.selectOneById(schedule.getHallId());
            if (hall == null) {
                return "{\"error\":\"影厅不存在\"}";
            }

            // 2. 查询所有座位
            List<Seat> seats = seatMapper.selectListByQuery(
                    QueryWrapper.create()
                            .eq(Seat::getScheduleId, scheduleId)
                            .orderBy(Seat::getRowNum, true)
                            .orderBy(Seat::getColNum, true)
            );

            // 3. 清理孤儿锁（DB 状态为 locked 但没有关联 pending 订单的座位）
            int released = cleanOrphanLocks(scheduleId, seats);
            if (released > 0) {
                log.info("getSeatMap 自动释放孤儿锁: scheduleId={}, count={}", scheduleId, released);
            }

            // 4. 构建二维座位矩阵
            int rowCount = hall.getRowCount() != null ? hall.getRowCount() : 0;
            int colCount = hall.getColCount() != null ? hall.getColCount() : 0;

            // 如果影厅的行列数未设置，从座位数据推断
            if (rowCount == 0 || colCount == 0) {
                rowCount = seats.stream().mapToInt(Seat::getRowNum).max().orElse(0);
                colCount = seats.stream().mapToInt(Seat::getColNum).max().orElse(0);
            }

            // 按行列索引座位
            Map<String, Seat> seatMap = new HashMap<>();
            for (Seat seat : seats) {
                String key = seat.getRowNum() + "_" + seat.getColNum();
                seatMap.put(key, seat);
            }

            // 构建二维网格
            List<List<Map<String, Object>>> seatGrid = new ArrayList<>();
            for (int row = 1; row <= rowCount; row++) {
                List<Map<String, Object>> rowSeats = new ArrayList<>();
                for (int col = 1; col <= colCount; col++) {
                    String key = row + "_" + col;
                    Seat seat = seatMap.get(key);
                    if (seat != null) {
                        Map<String, Object> seatInfo = new HashMap<>();
                        seatInfo.put("seatId", seat.getId().toString()); // 雪花 ID 字符串化，避免前端精度丢失
                        seatInfo.put("rowNum", seat.getRowNum());
                        seatInfo.put("colNum", seat.getColNum());
                        seatInfo.put("seatLabel", seat.getSeatLabel());
                        seatInfo.put("zone", seat.getZone() != null ? seat.getZone() : "regular");
                        seatInfo.put("status", seat.getStatus());
                        // 根据区域确定价格
                        BigDecimal price = "vip".equals(seat.getZone())
                                ? (schedule.getVipPrice() != null ? schedule.getVipPrice() : schedule.getPrice())
                                : schedule.getPrice();
                        seatInfo.put("price", price);
                        rowSeats.add(seatInfo);
                    } else {
                        // 过道/空位
                        Map<String, Object> emptySeat = new HashMap<>();
                        emptySeat.put("seatId", null);
                        emptySeat.put("rowNum", row);
                        emptySeat.put("colNum", col);
                        emptySeat.put("seatLabel", null);
                        emptySeat.put("zone", null);
                        emptySeat.put("status", "aisle");
                        emptySeat.put("price", null);
                        rowSeats.add(emptySeat);
                    }
                }
                seatGrid.add(rowSeats);
            }

            // 统计
            long availableCount = seats.stream().filter(s -> SeatStatusEnum.AVAILABLE.getValue().equals(s.getStatus())).count();
            long lockedCount = seats.stream().filter(s -> SeatStatusEnum.LOCKED.getValue().equals(s.getStatus())).count();
            long soldCount = seats.stream().filter(s -> SeatStatusEnum.SOLD.getValue().equals(s.getStatus())).count();

            Map<String, Object> result = new HashMap<>();
            result.put("scheduleId", scheduleId);
            result.put("hallId", hall.getId());
            result.put("hallName", hall.getName());
            result.put("hallType", hall.getHallType());
            result.put("rowCount", rowCount);
            result.put("colCount", colCount);
            result.put("standardPrice", schedule.getPrice());
            result.put("vipPrice", schedule.getVipPrice());
            result.put("seatGrid", seatGrid);
            result.put("availableCount", availableCount);
            result.put("lockedCount", lockedCount);
            result.put("soldCount", soldCount);

            // ★ ReAct 模式下 LLM 直接调此工具，需把 scheduleId 写回对话状态，供后续 Graph 接力下单
            String convId = ConversationContext.get();
            if (convId != null) {
                try {
                    ConversationState convState = stateManager.getState(convId);
                    convState.setScheduleId(scheduleId);
                    if (hall != null) convState.setHallName(hall.getName());
                    if (schedule != null) {
                        if (schedule.getFilmId() != null) convState.setFilmId(schedule.getFilmId());
                        if (schedule.getCinemaId() != null) convState.setCinemaId(schedule.getCinemaId());
                    }
                    stateManager.saveState(convId, convState);
                    log.info("getSeatMap 写回 scheduleId={}: conversationId={}", scheduleId, convId);
                } catch (Exception e) {
                    log.warn("getSeatMap 写回状态失败: conversationId={}", convId, e);
                }
            }

            String json = objectMapper.writeValueAsString(result);
            log.info("getSeatMap: scheduleId={}, rows={}, cols={}, available={}, locked={}, sold={}",
                    scheduleId, rowCount, colCount, availableCount, lockedCount, soldCount);
            return json;

        } catch (Exception e) {
            log.error("getSeatMap 查询失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @Override
    public String getToolName() {
        return "getSeatMap";
    }

    /**
     * 清理孤儿锁：DB status=locked 但未关联任何 pending 订单的座位 → 重置为 available。
     * 正常的 locked 座位应由 pending 订单持有（订单过期时由定时任务释放），
     * 这里只兜底清理无主锁（如用户选座后直接关掉页面、浏览器崩溃等场景）。
     */
    private int cleanOrphanLocks(Long scheduleId, List<Seat> seats) {
        List<Seat> lockedSeats = seats.stream()
                .filter(s -> SeatStatusEnum.LOCKED.getValue().equals(s.getStatus()))
                .collect(Collectors.toList());
        if (lockedSeats.isEmpty()) {
            return 0;
        }

        // 查询本场次所有 pending 订单
        List<Order> pendingOrders = orderMapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("scheduleId", scheduleId)
                        .eq("status", "pending"));
        if (pendingOrders.isEmpty()) {
            // 没有 pending 订单 → 所有 locked 座位都是孤儿锁，用乐观锁条件释放
            for (Seat seat : lockedSeats) {
                seat.setStatus(SeatStatusEnum.AVAILABLE.getValue());
                seatMapper.updateByQuery(
                        Seat.builder().status(SeatStatusEnum.AVAILABLE.getValue()).build(),
                        QueryWrapper.create()
                                .eq("id", seat.getId())
                                .eq("status", SeatStatusEnum.LOCKED.getValue()));
            }
            return lockedSeats.size();
        }

        // 查询 pending 订单关联的座位 ID
        List<Long> orderIds = pendingOrders.stream().map(Order::getId).collect(Collectors.toList());
        List<OrderSeat> orderSeats = orderSeatMapper.selectListByQuery(
                QueryWrapper.create().in("orderId", orderIds));
        Set<Long> validLockedSeatIds = orderSeats.stream()
                .map(OrderSeat::getSeatId)
                .collect(Collectors.toSet());

        // 释放不在任何 pending 订单中的 locked 座位（乐观锁条件更新）
        int released = 0;
        for (Seat seat : lockedSeats) {
            if (!validLockedSeatIds.contains(seat.getId())) {
                seat.setStatus(SeatStatusEnum.AVAILABLE.getValue());
                int updated = seatMapper.updateByQuery(
                        Seat.builder().status(SeatStatusEnum.AVAILABLE.getValue()).build(),
                        QueryWrapper.create()
                                .eq("id", seat.getId())
                                .eq("status", SeatStatusEnum.LOCKED.getValue()));
                if (updated > 0) {
                    released++;
                }
            }
        }
        return released;
    }

    @Override
    public String getDisplayName() {
        return "获取座位图";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        Long scheduleId = arguments.getLong("scheduleId");
        return String.format("[工具调用] 获取座位图 scheduleId=%d", scheduleId);
    }
}
