package com.limou.agent.ai.movie.tools;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limou.agent.ai.movie.ConversationContext;
import com.limou.agent.ai.movie.MovieStateManager;
import com.limou.agent.mapper.ScheduleMapper;
import com.limou.agent.mapper.SeatMapper;
import com.limou.agent.model.dto.movie.ConversationState;
import com.limou.agent.model.entity.Schedule;
import com.limou.agent.model.entity.Seat;
import com.limou.agent.model.enums.SeatStatusEnum;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 座位锁定工具
 * 使用数据库乐观锁 + Redis 分布式锁 + owner 归属标记防止超卖
 */
@Slf4j
@Component
public class LockSeatsTool extends BaseTool {

    private static final String LOCK_KEY_PREFIX = "seat:lock:";
    private static final String OWNER_KEY_PREFIX = "seat:owner:";
    /** Redis 锁 TTL（分钟），与锁座时长对齐 */
    private static final long LOCK_TTL_MINUTES = 15;

    @Resource
    private SeatMapper seatMapper;

    @Resource
    private ScheduleMapper scheduleMapper;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private MovieStateManager stateManager;

    @Tool(description = "锁定指定场次的座位。传入场次ID和座位ID数组。返回锁定结果JSON，成功则包含已锁座位信息和总价，失败则包含冲突座位和推荐替代")
    @Transactional(rollbackFor = Exception.class)
    public String lockSeats(
            @ToolParam(description = "场次ID") Long scheduleId,
            @ToolParam(description = "座位ID数组") List<Long> seatIds
    ) {
        try {
            if (seatIds == null || seatIds.isEmpty()) {
                return "{\"success\":false,\"error\":\"未指定座位\"}";
            }

            // 1. 查询座位当前状态
            List<Seat> seats = seatMapper.selectListByQuery(
                    QueryWrapper.create().in(Seat::getId, seatIds)
            );

            if (seats.size() != seatIds.size()) {
                Set<Long> foundIds = seats.stream().map(Seat::getId).collect(Collectors.toSet());
                List<Long> missingIds = seatIds.stream()
                        .filter(id -> !foundIds.contains(id))
                        .collect(Collectors.toList());
                return "{\"success\":false,\"error\":\"座位不存在: " + missingIds + "\"}";
            }

            // 2. 按状态分类：available → 直接可锁；locked → 查 owner 归属
            String convId = ConversationContext.get();
            List<Map<String, Object>> unavailableSeats = new ArrayList<>();
            List<Seat> availableSeats = new ArrayList<>();

            for (Seat seat : seats) {
                if (SeatStatusEnum.AVAILABLE.getValue().equals(seat.getStatus())) {
                    availableSeats.add(seat);
                } else if (SeatStatusEnum.LOCKED.getValue().equals(seat.getStatus())) {
                    // ★ 用 owner key 替代 isLocked() 判断归属（避免 TOCTOU）
                    String ownerKey = OWNER_KEY_PREFIX + scheduleId + ":" + seat.getId();
                    String existingOwner = (String) redissonClient.getBucket(ownerKey).get();
                    if (existingOwner == null) {
                        // owner key 已过期但 DB 仍是 locked → 孤儿锁，用乐观锁修复
                        int fixed = seatMapper.updateByQuery(
                                Seat.builder().status(SeatStatusEnum.AVAILABLE.getValue()).build(),
                                QueryWrapper.create()
                                        .eq("id", seat.getId())
                                        .eq("status", SeatStatusEnum.LOCKED.getValue()));
                        if (fixed > 0) {
                            availableSeats.add(seat);
                            log.info("自动释放孤儿锁: scheduleId={}, seat={}", scheduleId, seat.getSeatLabel());
                        } else {
                            // 并发时已被其他人改掉（如卖出了）→ 不可用
                            Map<String, Object> info = new HashMap<>();
                            info.put("seatId", seat.getId());
                            info.put("seatLabel", seat.getSeatLabel());
                            info.put("status", "sold");
                            unavailableSeats.add(info);
                        }
                    } else if (convId != null && convId.equals(existingOwner)) {
                        // 同一会话 → 幂等（从 unavailable 移除，后续走幂等成功逻辑）
                        // 不加入 availableSeats（不需要重复 DB 更新）
                    } else {
                        // 其他会话持有 → 冲突
                        Map<String, Object> info = new HashMap<>();
                        info.put("seatId", seat.getId());
                        info.put("seatLabel", seat.getSeatLabel());
                        info.put("status", seat.getStatus());
                        unavailableSeats.add(info);
                    }
                } else {
                    Map<String, Object> info = new HashMap<>();
                    info.put("seatId", seat.getId());
                    info.put("seatLabel", seat.getSeatLabel());
                    info.put("status", seat.getStatus());
                    unavailableSeats.add(info);
                }
            }

            if (!unavailableSeats.isEmpty()) {
                // ★ 幂等检查：过滤掉当前会话已锁定的座位
                List<Map<String, Object>> trulyUnavailable = new ArrayList<>();
                if (convId != null) {
                    try {
                        ConversationState convState = stateManager.getState(convId);
                        List<Long> existingSeatIds = convState.getSeatIds();
                        if (existingSeatIds != null && !existingSeatIds.isEmpty()) {
                            for (Map<String, Object> us : unavailableSeats) {
                                Long seatId = ((Number) us.get("seatId")).longValue();
                                if (!existingSeatIds.contains(seatId)) {
                                    trulyUnavailable.add(us);
                                }
                            }
                        } else {
                            trulyUnavailable.addAll(unavailableSeats);
                        }
                    } catch (Exception e) {
                        trulyUnavailable.addAll(unavailableSeats);
                    }
                } else {
                    trulyUnavailable.addAll(unavailableSeats);
                }

                // 如果所有冲突座位都是自己锁的且没有新的 availableSeats 需要锁 → 幂等成功
                if (trulyUnavailable.isEmpty() && availableSeats.isEmpty()) {
                    List<String> lockedLabels = unavailableSeats.stream()
                            .map(s -> (String) s.get("seatLabel"))
                            .collect(Collectors.toList());
                    Schedule schedule = scheduleMapper.selectOneById(scheduleId);
                    BigDecimal totalPrice = lockedLabels.isEmpty() ? BigDecimal.ZERO
                            : (schedule != null && schedule.getPrice() != null
                                    ? schedule.getPrice().multiply(BigDecimal.valueOf(lockedLabels.size()))
                                    : BigDecimal.ZERO);
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("lockedSeats", lockedLabels);
                    result.put("count", lockedLabels.size());
                    result.put("totalPrice", totalPrice);
                    result.put("message", "座位已锁定： " + String.join("、", lockedLabels) + "（之前已锁定）");
                    log.info("lockSeats 幂等返回: conversationId={}, seats={}", convId, lockedLabels);
                    return objectMapper.writeValueAsString(result);
                }

                if (!trulyUnavailable.isEmpty()) {
                    List<Map<String, Object>> alternatives = findAlternatives(scheduleId, trulyUnavailable);
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", false);
                    result.put("conflictSeats", trulyUnavailable);
                    result.put("alternatives", alternatives);
                    result.put("message", "部分座位已被占用，为您推荐附近可选座位～");
                    return objectMapper.writeValueAsString(result);
                }
                // trulyUnavailable 为空但有 availableSeats → 继续正常锁定流程
            }

            // 3. 获取 Redis 互斥锁（只对需要 DB 更新的 availableSeats 加锁）
            List<RLock> acquiredLocks = new ArrayList<>();
            try {
                for (Seat seat : availableSeats) {
                    RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + scheduleId + ":" + seat.getId());
                    if (lock.tryLock(3, LOCK_TTL_MINUTES, TimeUnit.MINUTES)) {
                        acquiredLocks.add(lock);
                    } else {
                        for (RLock acquired : acquiredLocks) {
                            try { acquired.unlock(); } catch (Exception ignored) {}
                        }
                        return "{\"success\":false,\"error\":\"座位锁定失败，请重试\"}";
                    }
                }

                // 4. DB 乐观锁：只更新 status='available' 的座位
                int ownerTtlSeconds = (int) (LOCK_TTL_MINUTES * 60);
                for (Seat seat : availableSeats) {
                    long updated = seatMapper.updateByQuery(
                            Seat.builder().status(SeatStatusEnum.LOCKED.getValue()).build(),
                            QueryWrapper.create()
                                    .eq(Seat::getId, seat.getId())
                                    .eq(Seat::getStatus, SeatStatusEnum.AVAILABLE.getValue())
                    );
                    if (updated == 0) {
                        for (RLock acquired : acquiredLocks) {
                            try { acquired.unlock(); } catch (Exception ignored) {}
                        }
                        return "{\"success\":false,\"error\":\"手慢了！😅 座位 " + seat.getSeatLabel() + " 已被别人抢走\"}";
                    }
                    // ★ 写入 owner 归属标记
                    String owner = convId != null ? convId : "anon";
                    redissonClient.getBucket(OWNER_KEY_PREFIX + scheduleId + ":" + seat.getId())
                            .set(owner, ownerTtlSeconds, TimeUnit.SECONDS);
                }

                // 5. 锁定成功
                List<String> lockedLabels = availableSeats.stream()
                        .map(Seat::getSeatLabel)
                        .collect(Collectors.toList());

                Schedule schedule = scheduleMapper.selectOneById(scheduleId);
                BigDecimal totalPrice = availableSeats.stream()
                        .map(s -> "vip".equals(s.getZone())
                                ? (schedule != null && schedule.getVipPrice() != null
                                        ? schedule.getVipPrice() : BigDecimal.ZERO)
                                : (schedule != null && schedule.getPrice() != null
                                        ? schedule.getPrice() : BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("lockedSeats", lockedLabels);
                result.put("lockedSeatIds", availableSeats.stream().map(Seat::getId).map(String::valueOf).collect(Collectors.toList()));
                result.put("count", lockedLabels.size());
                result.put("totalPrice", totalPrice);
                result.put("message", "太棒了！🎉 已为您锁定 " + String.join("、", lockedLabels));

                // 写回 ConversationState
                if (convId != null) {
                    try {
                        List<Long> lockedIds = availableSeats.stream().map(Seat::getId).collect(Collectors.toList());
                        ConversationState convState = stateManager.getState(convId);
                        convState.setSeatIds(lockedIds);
                        convState.setScheduleId(scheduleId);
                        stateManager.saveState(convId, convState);
                        log.info("lockSeats 写回 seatIds={} 到 Redis: conversationId={}", lockedIds, convId);
                    } catch (Exception e) {
                        log.warn("lockSeats 写回状态失败: conversationId={}", convId, e);
                    }
                }

                log.info("lockSeats 成功: scheduleId={}, seats={}", scheduleId, lockedLabels);
                return objectMapper.writeValueAsString(result);

            } catch (Exception e) {
                for (RLock acquired : acquiredLocks) {
                    try { acquired.unlock(); } catch (Exception ignored) {}
                }
                throw e;
            }

        } catch (Exception e) {
            log.error("lockSeats 失败", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 座位标签解析："5排6座" → {5, 6} */
    private static final Pattern ROW_COL_PATTERN = Pattern.compile("(\\d+)排(\\d+)座");

    /**
     * 查找可用座位作为替代推荐
     */
    private List<Map<String, Object>> findAlternatives(Long scheduleId, List<Map<String, Object>> unavailableSeats) {
        try {
            List<Seat> allSeats = seatMapper.selectListByQuery(
                    QueryWrapper.create()
                            .eq(Seat::getScheduleId, scheduleId)
                            .eq(Seat::getStatus, SeatStatusEnum.AVAILABLE.getValue()));
            if (allSeats.isEmpty()) {
                return Collections.emptyList();
            }

            TreeMap<Integer, List<Seat>> byRow = allSeats.stream()
                    .filter(s -> s.getRowNum() != null)
                    .collect(Collectors.groupingBy(Seat::getRowNum, TreeMap::new, Collectors.toList()));

            int anchorRow = -1;
            int anchorCol = -1;
            if (unavailableSeats != null && !unavailableSeats.isEmpty()) {
                for (Map<String, Object> us : unavailableSeats) {
                    String label = String.valueOf(us.get("seatLabel"));
                    Matcher m = ROW_COL_PATTERN.matcher(label == null ? "" : label);
                    if (m.find()) {
                        anchorRow = Integer.parseInt(m.group(1));
                        anchorCol = Integer.parseInt(m.group(2));
                        break;
                    }
                }
            }

            int centerRow = (byRow.firstKey() + byRow.lastKey()) / 2;
            int maxCol = byRow.values().stream()
                    .flatMap(List::stream)
                    .mapToInt(s -> s.getColNum() != null ? s.getColNum() : 0)
                    .max().orElse(0);
            double midCol = maxCol / 2.0;

            int needCount = Math.max(2, unavailableSeats == null ? 1 : unavailableSeats.size());

            final int baseRow = anchorRow > 0 ? anchorRow : centerRow;
            List<Integer> rows = new ArrayList<>(byRow.keySet());
            rows.sort(Comparator
                    .comparingInt((Integer r) -> Math.abs(r - baseRow))
                    .thenComparingInt(r -> Math.abs(r - centerRow)));

            List<Map<String, Object>> result = new ArrayList<>();
            Map<String, Object> bestSingle = null;
            double bestSingleDist = Double.MAX_VALUE;
            final int MAX_ALTS = 12;
            for (int r : rows) {
                if (result.size() >= MAX_ALTS) {
                    break;
                }
                List<Seat> rowSeats = new ArrayList<>(byRow.get(r));
                rowSeats.sort(Comparator.comparingInt(s -> s.getColNum() != null ? s.getColNum() : 0));
                List<List<Seat>> runs = splitConsecutiveRuns(rowSeats);
                if (runs.isEmpty()) {
                    continue;
                }
                double targetCol = (anchorRow == r && anchorCol > 0) ? anchorCol : midCol;
                List<Seat> bestRun = null;
                double bestDist = Double.MAX_VALUE;
                for (List<Seat> run : runs) {
                    double runCenter = (run.get(0).getColNum() + run.get(run.size() - 1).getColNum()) / 2.0;
                    double dist = Math.abs(runCenter - targetCol);
                    if (run.size() == 1 && dist < bestSingleDist) {
                        bestSingleDist = dist;
                        bestSingle = toAltMap(run.get(0));
                    }
                    if (run.size() >= 2 && dist < bestDist - 1e-9) {
                        bestDist = dist;
                        bestRun = run;
                    }
                }
                if (bestRun == null) {
                    continue;
                }
                List<Seat> take = bestWindow(bestRun, needCount, targetCol);
                for (Seat s : take) {
                    result.add(toAltMap(s));
                }
            }
            if (result.isEmpty() && bestSingle != null) {
                result.add(bestSingle);
            }
            return result;
        } catch (Exception e) {
            log.warn("findAlternatives 失败: scheduleId={}", scheduleId, e);
            return Collections.emptyList();
        }
    }

    private List<List<Seat>> splitConsecutiveRuns(List<Seat> rowSeats) {
        List<List<Seat>> runs = new ArrayList<>();
        List<Seat> cur = new ArrayList<>();
        int prevCol = Integer.MIN_VALUE;
        for (Seat s : rowSeats) {
            int col = s.getColNum() != null ? s.getColNum() : 0;
            if (!cur.isEmpty() && col - prevCol != 1) {
                runs.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(s);
            prevCol = col;
        }
        if (!cur.isEmpty()) {
            runs.add(cur);
        }
        return runs;
    }

    private List<Seat> bestWindow(List<Seat> run, int need, double targetCol) {
        if (run.size() <= need) {
            return run;
        }
        List<Seat> best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i + need <= run.size(); i++) {
            int left = run.get(i).getColNum();
            int right = run.get(i + need - 1).getColNum();
            double center = (left + right) / 2.0;
            double dist = Math.abs(center - targetCol);
            if (dist < bestDist - 1e-9) {
                bestDist = dist;
                best = run.subList(i, i + need);
            }
        }
        return best != null ? best : run;
    }

    private Map<String, Object> toAltMap(Seat s) {
        Map<String, Object> map = new HashMap<>();
        map.put("seatId", String.valueOf(s.getId()));
        map.put("seatLabel", s.getSeatLabel());
        map.put("zone", s.getZone());
        return map;
    }

    /**
     * 释放所有孤儿锁：owner key 已过期但 DB 状态仍为 locked 的座位 → 重置为 available。
     * 使用乐观锁条件更新，避免覆盖并发操作。
     */
    public void releaseStaleLocks() {
        try {
            List<Seat> lockedSeats = seatMapper.selectListByQuery(
                    QueryWrapper.create().eq(Seat::getStatus, SeatStatusEnum.LOCKED.getValue())
            );
            int released = 0;
            for (Seat seat : lockedSeats) {
                // ★ 用乐观锁条件更新：只有 status 仍是 locked 时才改回 available
                // 防止覆盖并发中刚刚被售出（sold）的座位
                int updated = seatMapper.updateByQuery(
                        Seat.builder().status(SeatStatusEnum.AVAILABLE.getValue()).build(),
                        QueryWrapper.create()
                                .eq("id", seat.getId())
                                .eq("status", SeatStatusEnum.LOCKED.getValue()));
                if (updated > 0) {
                    released++;
                }
            }
            if (released > 0) {
                log.info("释放孤儿座位锁: {} 个座位已恢复为 available", released);
            }
        } catch (Exception e) {
            log.error("释放孤儿锁失败", e);
        }
    }

    @Override
    public String getToolName() {
        return "lockSeats";
    }

    @Override
    public String getDisplayName() {
        return "锁定座位";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        Long scheduleId = arguments.getLong("scheduleId");
        return String.format("[工具调用] 锁定座位 scheduleId=%d", scheduleId);
    }
}
