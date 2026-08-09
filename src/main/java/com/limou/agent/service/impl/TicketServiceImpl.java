package com.limou.agent.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.limou.agent.exception.BusinessException;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.mapper.TicketMapper;
import com.limou.agent.model.entity.Order;
import com.limou.agent.model.entity.Schedule;
import com.limou.agent.model.entity.Seat;
import com.limou.agent.model.entity.Ticket;
import com.limou.agent.model.enums.OrderStatusEnum;
import com.limou.agent.model.enums.TicketStatusEnum;
import com.limou.agent.model.vo.TicketVO;
import com.limou.agent.service.OrderService;
import com.limou.agent.service.ScheduleService;
import com.limou.agent.service.TicketService;
import com.limou.agent.service.UserWatchedFilmService;
import com.limou.agent.util.ScheduleTimeUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 电影票 服务层实现。
 *
 * @author 李振南
 */
@Service
@Slf4j
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket> implements TicketService {

    private static final Random RANDOM = new Random();

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    @Lazy
    private OrderService orderService;

    @Autowired
    private UserWatchedFilmService userWatchedFilmService;

    /** 生成 8 位数字取票码（前导 0 补位） */
    private String generateTicketCode() {
        return String.format("%08d", RANDOM.nextInt(100000000));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Ticket> createTickets(Long orderId, Long scheduleId, List<Seat> seats) {
        log.info("[createTickets] 进入, orderId={}, scheduleId={}, seats.size={}",
                orderId, scheduleId, seats == null ? 0 : seats.size());
        try {
            if (orderId == null || scheduleId == null || CollUtil.isEmpty(seats)) {
                log.warn("[createTickets] 参数无效, orderId={}, scheduleId={}, seats={}", orderId, scheduleId, seats);
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数无效");
            }
            // 幂等：订单已有票则跳过（支付成功/重复调用时不再重复生成）
            List<Ticket> existingTickets = listByOrder(orderId);
            if (CollUtil.isNotEmpty(existingTickets)) {
                log.info("[createTickets] 订单 {} 已有 {} 张票，跳过生成", orderId, existingTickets.size());
                return existingTickets;
            }
            // 现有取票码集合（唯一索引 uk_ticketCode 兜底，这里先查重避免批量冲突）
            Set<String> existing = mapper.selectListByQuery(QueryWrapper.create().select("ticketCode"))
                    .stream().map(Ticket::getTicketCode).collect(Collectors.toSet());
            log.info("[createTickets] 全表现有取票码 {} 个", existing.size());

            List<Ticket> tickets = new ArrayList<>();
            for (Seat seat : seats) {
                String code;
                do {
                    code = generateTicketCode();
                } while (existing.contains(code));
                existing.add(code);

                Ticket t = new Ticket();
                t.setOrderId(orderId);
                t.setScheduleId(scheduleId);
                t.setSeatId(seat.getId());
                t.setSeatLabel(seat.getSeatLabel());
                t.setTicketCode(code);
                t.setStatus(TicketStatusEnum.UNUSED.getValue());
                tickets.add(t);
                log.info("[createTickets] 待插入: orderId={}, seatId={}, seatLabel={}, code={}",
                        orderId, seat.getId(), seat.getSeatLabel(), code);
            }
            boolean saved = saveBatch(tickets);
            log.info("[createTickets] saveBatch 结果 saved={}, 条数={}", saved, tickets.size());
            if (!saved) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "票生成失败");
            }
            log.info("[createTickets] 订单 {} 成功生成 {} 张票", orderId, tickets.size());
            return tickets;
        } catch (Exception e) {
            // 即使异常被外层调用方吞掉，也要落盘完整堆栈便于排查
            log.error("[createTickets] 生成票异常, orderId={}, scheduleId={}, seats.size={}",
                    orderId, scheduleId, seats == null ? 0 : seats.size(), e);
            throw e;
        }
    }

    @Override
    public Ticket getByTicketCode(String ticketCode) {
        if (ticketCode == null || ticketCode.isBlank()) {
            return null;
        }
        return getOne(QueryWrapper.create().eq("ticketCode", ticketCode.trim()));
    }

    @Override
    public TicketVO queryTicket(String ticketCode) {
        Ticket ticket = getByTicketCode(ticketCode);
        if (ticket == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "票不存在或取票码有误");
        }
        return buildTicketVO(ticket);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO checkinTicket(String ticketCode, Long operatorId) {
        Ticket ticket = getByTicketCode(ticketCode);
        if (ticket == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "票不存在或取票码有误");
        }
        // 幂等 + 状态校验：已核销/已退票/已过期 均不可再核销
        Integer st = ticket.getStatus();
        if (st != null && TicketStatusEnum.CHECKED == TicketStatusEnum.getEnumByValue(st)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "该票已于 " + (ticket.getCheckedInAt() != null ? ticket.getCheckedInAt() : "") + " 核销");
        }
        if (st != null && TicketStatusEnum.REFUNDED == TicketStatusEnum.getEnumByValue(st)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该票已退票，无法核销");
        }
        // 已过期（落库 status=3，由定时任务写入；未落库时走下方 isExpired 动态判定）
        if (st != null && TicketStatusEnum.EXPIRED == TicketStatusEnum.getEnumByValue(st)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该票已过期，无法核销");
        }
        if (isExpired(ticket)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该票已过期，无法核销");
        }

        // 校验订单状态：仅已支付订单可核销
        Order order = orderService.getById(ticket.getOrderId());
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "订单不存在");
        }
        if (!OrderStatusEnum.PAID.getValue().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "订单未支付或已取消/退款，无法核销");
        }

        // 核销（乐观条件更新：仅当仍是"未使用"状态才更新，防并发双核销）
        Ticket update = new Ticket();
        update.setStatus(1);
        update.setCheckedInAt(LocalDateTime.now());
        update.setCheckedBy(operatorId);
        int updated = mapper.updateByQuery(update,
                QueryWrapper.create()
                        .eq("id", ticket.getId())
                        .eq("status", TicketStatusEnum.UNUSED.getValue()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "核销失败，该票状态已变化（可能已被核销），请刷新重试");
        }
        ticket.setStatus(1);
        ticket.setCheckedInAt(update.getCheckedInAt());
        ticket.setCheckedBy(operatorId);
        log.info("管理员 {} 核销票 {}（订单 {} 座位 {}）", operatorId, ticket.getTicketCode(),
                ticket.getOrderId(), ticket.getSeatLabel());

        // 核销后检查订单下所有票是否都已核销 → 标记已完成
        List<Ticket> allTickets = listByOrder(ticket.getOrderId());
        boolean allChecked = allTickets.stream().allMatch(t ->
                t.getStatus() != null && TicketStatusEnum.CHECKED == TicketStatusEnum.getEnumByValue(t.getStatus()));
        if (allChecked) {
            order.setStatus("completed");
            orderService.updateById(order);
            log.info("订单 {} 所有票已核销，状态变更为 completed", order.getId());

            Schedule schedule = scheduleService.getById(ticket.getScheduleId());
            if (schedule != null && schedule.getFilmId() != null) {
                userWatchedFilmService.markAsWatched(order.getUserId(), schedule.getFilmId());
                log.info("核销完成自动标记看过: userId={}, filmId={}", order.getUserId(), schedule.getFilmId());
            }
        }

        return buildTicketVO(ticket);
    }

    @Override
    public boolean hasUsedTicket(Long orderId) {
        if (orderId == null) {
            return false;
        }
        return count(QueryWrapper.create().eq("orderId", orderId).eq("status", 1)) > 0;
    }

    @Override
    public List<Ticket> listByOrder(Long orderId) {
        if (orderId == null) {
            return new ArrayList<>();
        }
        return list(QueryWrapper.create().eq("orderId", orderId));
    }

    @Override
    public Set<Long> getCheckedOrderIds(Collection<Long> orderIds) {
        if (CollUtil.isEmpty(orderIds)) {
            return new HashSet<>();
        }
        return mapper.selectListByQuery(QueryWrapper.create()
                        .in("orderId", orderIds)
                        .eq("status", TicketStatusEnum.CHECKED.getValue())
                        .select("orderId"))
                .stream().map(Ticket::getOrderId).collect(Collectors.toSet());
    }

    @Override
    public Map<Long, List<TicketVO>> getTicketsMapByOrderIds(Collection<Long> orderIds) {
        if (CollUtil.isEmpty(orderIds)) {
            return new HashMap<>();
        }
        List<Ticket> tickets = list(QueryWrapper.create().in("orderId", orderIds));
        if (CollUtil.isEmpty(tickets)) {
            return new HashMap<>();
        }
        return tickets.stream().collect(Collectors.groupingBy(Ticket::getOrderId,
                Collectors.mapping(t -> buildTicketVO(t, null), Collectors.toList())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markRefunded(Long orderId) {
        if (orderId == null) {
            return 0;
        }
        // 仅未核销/未使用的票置为已退票（已核销/已退票/已过期的不动）
        Ticket update = new Ticket();
        update.setStatus(TicketStatusEnum.REFUNDED.getValue());
        return mapper.updateByQuery(update,
                QueryWrapper.create()
                        .eq("orderId", orderId)
                        .and("(status = ? OR status IS NULL)", TicketStatusEnum.UNUSED.getValue()));
    }

    /**
     * 票是否已过期：未核销且场次已结束（跨天场次由 ScheduleTimeUtil 正确处理）。
     */
    private boolean isExpired(Ticket ticket) {
        if (ticket.getStatus() != null && TicketStatusEnum.UNUSED != TicketStatusEnum.getEnumByValue(ticket.getStatus())) {
            return false;
        }
        Schedule schedule = scheduleService.getById(ticket.getScheduleId());
        return ScheduleTimeUtil.isEnded(schedule);
    }

    /**
     * 展示状态：存储值(0/1/2) + 动态判定「已过期(3)」。
     * 已过期不落库，查询/核销时实时计算，避免依赖定时任务。
     */
    private Integer resolveStatus(Ticket ticket) {
        if (ticket.getStatus() != null && TicketStatusEnum.UNUSED != TicketStatusEnum.getEnumByValue(ticket.getStatus())) {
            return ticket.getStatus();
        }
        return isExpired(ticket)
                ? TicketStatusEnum.EXPIRED.getValue()
                : TicketStatusEnum.UNUSED.getValue();
    }

    @Override
    public List<TicketVO> getTicketsByOrder(Long orderId) {
        List<Ticket> tickets = listByOrder(orderId);
        if (CollUtil.isEmpty(tickets)) {
            return new ArrayList<>();
        }
        Order order = orderService.getById(orderId);
        return tickets.stream().map(t -> buildTicketVO(t, order)).collect(Collectors.toList());
    }

    /** 组装票 + 订单冗余信息（单票查询用） */
    private TicketVO buildTicketVO(Ticket ticket) {
        return buildTicketVO(ticket, orderService.getById(ticket.getOrderId()));
    }

    /** 组装票 + 订单冗余信息 */
    private TicketVO buildTicketVO(Ticket ticket, Order order) {
        TicketVO vo = new TicketVO();
        vo.setId(ticket.getId());
        vo.setOrderId(ticket.getOrderId());
        vo.setScheduleId(ticket.getScheduleId());
        vo.setSeatId(ticket.getSeatId());
        vo.setSeatLabel(ticket.getSeatLabel());
        vo.setTicketCode(ticket.getTicketCode());
        vo.setStatus(resolveStatus(ticket));
        vo.setCheckedInAt(ticket.getCheckedInAt());
        vo.setCheckedBy(ticket.getCheckedBy());

        if (order != null) {
            vo.setOrderNo(order.getOrderNo());
            vo.setOrderStatus(order.getStatus());
            vo.setFilmName(order.getFilmName());
            vo.setCinemaName(order.getCinemaName());
            vo.setHallName(order.getHallName());
            vo.setScheduleTime(order.getScheduleTime());
        }
        return vo;
    }
}
