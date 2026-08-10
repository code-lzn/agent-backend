package com.limou.agent.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.limou.agent.exception.BusinessException;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.mapper.OrderMapper;
import com.limou.agent.mapper.OrderSeatMapper;
import com.limou.agent.model.entity.Hall;
import com.limou.agent.model.entity.Order;
import com.limou.agent.model.entity.OrderSeat;
import com.limou.agent.model.entity.Schedule;
import com.limou.agent.model.vo.SeatMapVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.limou.agent.model.entity.Seat;
import com.limou.agent.model.enums.SeatStatusEnum;
import com.limou.agent.mapper.SeatMapper;
import com.limou.agent.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 座位 服务层实现。
 *
 * @author 李振南
 */
@Service
public class SeatServiceImpl extends ServiceImpl<SeatMapper, Seat> implements SeatService {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private HallService hallService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderSeatMapper orderSeatMapper;

    @Override
    public SeatMapVO getSeatMap(Long scheduleId) {
        if (scheduleId == null || scheduleId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "场次ID无效");
        }

        // 1. 查询场次信息
        Schedule schedule = scheduleService.getById(scheduleId);
        if (schedule == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "场次不存在");
        }

        // 2. 查询影厅信息
        Hall hall = hallService.getById(schedule.getHallId());
        if (hall == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "影厅不存在");
        }

        // 3. 查询座位列表
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("scheduleId", scheduleId)
                .eq("isDelete", 0)
                .orderBy("rowNum", true)
                .orderBy("colNum", true);
        List<Seat> seats = mapper.selectListByQuery(queryWrapper);

        // 3.5 兜底清理孤儿锁（locked 但无关联 pending 订单的座位）
        cleanOrphanLocks(scheduleId, seats);

        // 4. 组装
        SeatMapVO vo = new SeatMapVO();
        vo.setScheduleId(scheduleId);
        vo.setPrice(schedule.getPrice());
        vo.setVipPrice(schedule.getVipPrice());
        vo.setHallId(hall.getId());
        vo.setHallName(hall.getName());
        vo.setHallType(hall.getHallType());
        // rowCount/colCount 优先用影厅布局；若场次实际座位超出（旧场次座位未随影厅布局重建），
        // 按实际座位推断，保证 H5 按物理格遍历时全部座位可见、不错位
        int layoutRows = hall.getRowCount() != null ? hall.getRowCount() : 0;
        int layoutCols = hall.getColCount() != null ? hall.getColCount() : 0;
        int maxRow = seats.stream().mapToInt(Seat::getRowNum).max().orElse(0);
        int maxCol = seats.stream().mapToInt(Seat::getColNum).max().orElse(0);
        vo.setRowCount(Math.max(layoutRows, maxRow));
        vo.setColCount(Math.max(layoutCols, maxCol));
        vo.setSeats(seats);
        // 布局信息（从影厅 seatTemplate 带出，前端按物理格遍历渲染）
        if (cn.hutool.core.util.StrUtil.isNotBlank(hall.getSeatTemplate())) {
            try {
                cn.hutool.json.JSONObject tmpl = new cn.hutool.json.JSONObject(hall.getSeatTemplate());
                if (tmpl.containsKey("rowOverrides")) {
                    cn.hutool.json.JSONObject overrides = tmpl.getJSONObject("rowOverrides");
                    Map<Integer, Integer> rowOverrides = new HashMap<>();
                    for (Map.Entry<String, Object> entry : overrides.entrySet()) {
                        try {
                            rowOverrides.put(Integer.parseInt(entry.getKey()), ((Number) entry.getValue()).intValue());
                        } catch (Exception ignored) {
                        }
                    }
                    if (!rowOverrides.isEmpty()) vo.setRowOverrides(rowOverrides);
                }
                if (tmpl.containsKey("aisleRows")) {
                    List<Integer> aisleRows = new ArrayList<>();
                    for (Object r : tmpl.getJSONArray("aisleRows")) {
                        aisleRows.add(((Number) r).intValue());
                    }
                    vo.setAisleRows(aisleRows);
                }
                if (tmpl.containsKey("aisleCols")) {
                    List<Integer> aisleCols = new ArrayList<>();
                    for (Object c : tmpl.getJSONArray("aisleCols")) {
                        aisleCols.add(((Number) c).intValue());
                    }
                    vo.setAisleCols(aisleCols);
                }
            } catch (Exception ignored) {
            }
        }

        return vo;
    }

    /**
     * 兜底清理孤儿锁：DB status=locked 但未关联任何 pending 订单的座位 → 重置为 available。
     * 正常的 locked 座位由 pending 订单持有，订单超时时由定时任务释放；
     * 这里只清理无主锁（用户选座后直接关页面、浏览器崩溃等异常场景）。
     */
    private void cleanOrphanLocks(Long scheduleId, List<Seat> seats) {
        List<Seat> lockedSeats = seats.stream()
                .filter(s -> SeatStatusEnum.LOCKED.getValue().equals(s.getStatus()))
                .collect(Collectors.toList());
        if (lockedSeats.isEmpty()) {
            return;
        }

        // 查询本场次所有 pending 订单
        List<Order> pendingOrders = orderMapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("scheduleId", scheduleId)
                        .eq("status", "pending"));
        if (pendingOrders.isEmpty()) {
            for (Seat seat : lockedSeats) {
                seat.setStatus(SeatStatusEnum.AVAILABLE.getValue());
                // ★ 乐观锁条件更新：只有 status 仍是 locked 才改，防止覆盖并发操作
                mapper.updateByQuery(
                        Seat.builder().status(SeatStatusEnum.AVAILABLE.getValue()).build(),
                        QueryWrapper.create()
                                .eq("id", seat.getId())
                                .eq("status", SeatStatusEnum.LOCKED.getValue()));
            }
            return;
        }

        // 查询 pending 订单关联的座位 ID
        List<Long> orderIds = pendingOrders.stream().map(Order::getId).collect(Collectors.toList());
        List<OrderSeat> orderSeats = orderSeatMapper.selectListByQuery(
                QueryWrapper.create().in("orderId", orderIds));
        Set<Long> validLockedSeatIds = orderSeats.stream()
                .map(OrderSeat::getSeatId)
                .collect(Collectors.toSet());

        for (Seat seat : lockedSeats) {
            if (!validLockedSeatIds.contains(seat.getId())) {
                seat.setStatus(SeatStatusEnum.AVAILABLE.getValue());
                // ★ 乐观锁条件更新：只有 status 仍是 locked 才改
                mapper.updateByQuery(
                        Seat.builder().status(SeatStatusEnum.AVAILABLE.getValue()).build(),
                        QueryWrapper.create()
                                .eq("id", seat.getId())
                                .eq("status", SeatStatusEnum.LOCKED.getValue()));
            }
        }
    }
}
