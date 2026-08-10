package com.limou.agent.ai.movie.graph.nodes;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.limou.agent.ai.graph.GraphNode;
import com.limou.agent.ai.movie.MovieStateManager;
import com.limou.agent.ai.movie.graph.MovieGraphState;
import com.limou.agent.ai.movie.graph.MovieIntent;
import com.limou.agent.ai.movie.tools.GetSeatMapTool;
import com.limou.agent.ai.movie.tools.LockSeatsTool;
import com.limou.agent.ai.movie.tools.SearchSchedulesTool;
import com.limou.agent.model.dto.movie.ConversationState;
import com.limou.agent.model.enums.SeatStatusEnum;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 锁定座位节点
 * <p>
 * 支持两种模式：
 * 1. 用户指定具体座位（seatIds 非空）→ 直接锁定
 * 2. 用户表达选座偏好（"帮我选中间3个座位"，有 preferredSeatZone/ticketCount 但无 seatIds）
 *    → 自动调 getSeatMap 拿座位图，按偏好选连续可用座位，再锁定
 * <p>
 * 执行后会将 lockedSeatIds 写回 ConversationState 并持久化到 Redis，
 * 确保下一轮 create_order 能直接从状态中获取已锁定的座位。
 */
@Slf4j
public class LockSeatsNode implements GraphNode<MovieGraphState> {

    private final LockSeatsTool tool;
    private final GetSeatMapTool getSeatMapTool;
    private final SearchSchedulesTool searchSchedulesTool;
    private final MovieStateManager stateManager;

    public LockSeatsNode(LockSeatsTool tool, GetSeatMapTool getSeatMapTool,
                         SearchSchedulesTool searchSchedulesTool, MovieStateManager stateManager) {
        this.tool = tool;
        this.getSeatMapTool = getSeatMapTool;
        this.searchSchedulesTool = searchSchedulesTool;
        this.stateManager = stateManager;
    }

    @Override
    public MovieGraphState execute(MovieGraphState state) {
        if (state.isBlocked()) {
            return state;
        }

        ConversationState convState = state.getConvState();

        if (convState.getScheduleId() == null) {
            // ★ 自动解析场次：用户文字指定场次（"去XX影院选14:39场次"）时 scheduleId 可能尚未写回，
            //   用已收集的影片/影院/日期/时间/厅信息查场次并匹配唯一场次，打通"一句话下单"链路
            Long resolvedScheduleId = resolveScheduleId(convState);
            if (resolvedScheduleId != null) {
                convState.setScheduleId(resolvedScheduleId);
                stateManager.saveState(state.getConversationId(), convState);
                log.info("LockSeats 自动解析场次 scheduleId={}: conversationId={}",
                        resolvedScheduleId, state.getConversationId());
            } else {
                state.setToolResult("{\"error\":\"请先选择场次\"}");
                state.setToolName(MovieIntent.LOCK_SEATS.getCode());
                return state;
            }
        }

        List<Long> seatIds = convState.getSeatIds();

        // ★ 用户指定了具体座位标签（如 "1排1座"），从座位图查找对应的 seatId
        if ((seatIds == null || seatIds.isEmpty())
                && convState.getSeatLabels() != null && !convState.getSeatLabels().isEmpty()) {
            String seatMapJson = getSeatMapTool.getSeatMap(convState.getScheduleId());
            seatIds = resolveSeatLabels(seatMapJson, convState.getSeatLabels());
            if (!seatIds.isEmpty()) {
                convState.setSeatIds(seatIds);
                log.info("LockSeats seatLabels→seatId: labels={}, ids={}",
                        convState.getSeatLabels(), seatIds);
            }
        }

        // ★ 自动选座：用户明确让 AI 选座（有选座偏好 或 消息里委托"帮我选/直接下单/就按你推荐的"）但未指定具体座位。
        //   只报票数（"两位"）没有偏好/委托 → 不自动选座（此时 resolveIntent 已降级 get_seat_map，这里兜底防御），返回"请先选择座位"
        if ((seatIds == null || seatIds.isEmpty())
                && convState.canAutoPickSeats(state.getUserMessage())) {
            int count = convState.getTicketCount() != null && convState.getTicketCount() > 0
                    ? convState.getTicketCount()
                    : 1;
            String zone = has(convState.getPreferredSeatZone()) ? convState.getPreferredSeatZone() : "中间";
            String seatMapJson = getSeatMapTool.getSeatMap(convState.getScheduleId());
            seatIds = autoPickSeats(seatMapJson, zone, count);
            if (seatIds.isEmpty()) {
                state.setToolResult("{\"success\":false,\"conflictSeats\":[],\"message\":\"没有找到可选的连续座位，请手动选座\"}");
                state.setToolName(MovieIntent.LOCK_SEATS.getCode());
                return state;
            }
            convState.setSeatIds(seatIds);
            log.info("LockSeats 自动选座: zone={}, count={}, seatIds={}",
                    zone, count, seatIds);
        }

        if (seatIds == null || seatIds.isEmpty()) {
            state.setToolResult("{\"error\":\"请先选择座位\"}");
            state.setToolName(MovieIntent.LOCK_SEATS.getCode());
            return state;
        }

        String result = tool.lockSeats(convState.getScheduleId(), seatIds);

        state.setToolResult(result);
        state.setToolName(MovieIntent.LOCK_SEATS.getCode());

        // 解析工具返回，将 lockedSeatIds 写回 ConversationState 并持久化到 Redis
        try {
            JSONObject json = JSONUtil.parseObj(result);
            if (json.getBool("success", false)) {
                List<Long> lockedIds = json.getJSONArray("lockedSeatIds")
                        .stream()
                        .map(o -> Long.valueOf(o.toString()))
                        .collect(Collectors.toList());
                if (!lockedIds.isEmpty()) {
                    convState.setSeatIds(lockedIds);
                    stateManager.saveState(state.getConversationId(), convState);
                    log.info("LockSeats 写回 seatIds={}: conversationId={}", lockedIds, state.getConversationId());
                }
            }
        } catch (Exception e) {
            log.warn("LockSeats 结果解析失败，跳过状态写回: conversationId={}", state.getConversationId(), e);
        }

        return state;
    }

    /** 场次未解析时，用已收集的影片/影院/日期/时间/厅信息查场次并匹配唯一场次（优先按开始时间精确匹配） */
    private Long resolveScheduleId(ConversationState convState) {
        try {
            if (convState.getFilmId() == null) return null;
            String result = searchSchedulesTool.searchSchedules(
                    convState.getFilmId(),
                    convState.getCinemaId(),
                    convState.getShowDate(),
                    convState.getHallType(),
                    convState.getStartTime(),
                    convState.getHallName());
            JSONObject obj = JSONUtil.parseObj(result);
            JSONArray sessions = obj.getJSONArray("sessions");
            if (sessions == null || sessions.isEmpty()) return null;

            String requestedTime = convState.getStartTime();
            if (requestedTime != null && !requestedTime.isBlank()) {
                for (int i = 0; i < sessions.size(); i++) {
                    JSONObject s = sessions.getJSONObject(i);
                    if (sameTime(requestedTime, s.getStr("startTime"))) {
                        return s.getLong("scheduleId");
                    }
                }
            }
            // 影院名匹配唯一场次（多个匹配则不确定，返回 null 不自动选）
            JSONObject matched = null;
            String cinemaName = convState.getCinemaName();
            if (has(cinemaName)) {
                for (int i = 0; i < sessions.size(); i++) {
                    JSONObject s = sessions.getJSONObject(i);
                    String cn = s.getStr("cinemaName");
                    if (cn != null && cn.contains(cinemaName)) {
                        if (matched != null) return null;
                        matched = s;
                    }
                }
                if (matched != null) return matched.getLong("scheduleId");
            }
        } catch (Exception e) {
            log.warn("LockSeats 自动解析场次失败: conversationId={}", e);
        }
        return null;
    }

    private boolean sameTime(String a, String b) {
        if (a == null || b == null) return false;
        try {
            return LocalTime.parse(a.trim()).equals(LocalTime.parse(b.trim()));
        } catch (Exception ignored) {
            return a.trim().equals(b.trim());
        }
    }

    /**
     * 根据选座偏好自动挑选连续可用座位。
     *
     * @param seatMapJson getSeatMap 返回的座位图 JSON
     * @param zone        偏好区域：中间/靠前/靠后/靠边/全场（默认中间）
     * @param count       需要的连续座位数（全场模式忽略，自动选全部可用座位）
     * @return 选中的 seatId 列表（找不到返回空）
     */
    private List<Long> autoPickSeats(String seatMapJson, String zone, int count) {
        List<Long> picked = new ArrayList<>();
        try {
            JSONObject map = JSONUtil.parseObj(seatMapJson);
            JSONArray grid = map.getJSONArray("seatGrid");
            if (grid == null || grid.isEmpty() || count <= 0) {
                return picked;
            }

            int rowCount = grid.size();
            int colCount = map.getInt("colCount", 0);
            String z = zone == null ? "" : zone;

            // ★ 包场/全场：锁定整个影厅所有可用座位（跨所有行，不受单排连续性限制）
            if ("全场".equals(z) || "包场".equals(z)) {
                List<Long> all = new ArrayList<>();
                for (int r = 0; r < rowCount; r++) {
                    JSONArray row = grid.getJSONArray(r);
                    if (row == null) {
                        continue;
                    }
                    for (int c = 0; c < row.size(); c++) {
                        Object cell = row.get(c);
                        if (cell == null) {
                            continue;
                        }
                        JSONObject seat = (JSONObject) cell;
                        if (SeatStatusEnum.AVAILABLE.getValue().equals(seat.getStr("status"))) {
                            Long sid = seat.getLong("seatId");
                            if (sid != null) {
                                all.add(sid);
                            }
                        }
                    }
                }
                log.info("自动选座(全场): 共选 {} 个可用座位", all.size());
                return all;
            }

            int startRow;
            int endRow;
            switch (z) {
                case "第一排" -> {
                    startRow = 0;
                    endRow = 1; // 只取第 1 排
                }
                case "最后一排" -> {
                    startRow = rowCount - 1;
                    endRow = rowCount; // 只取最后 1 排
                }
                case "靠前" -> {
                    startRow = 0;
                    endRow = Math.max(1, (int) Math.ceil(rowCount * 0.3));
                }
                case "靠后" -> {
                    startRow = (int) Math.floor(rowCount * 0.7);
                    endRow = rowCount;
                }
                case "靠边" -> {
                    startRow = 0;
                    endRow = rowCount;
                }
                default -> { // 中间
                    startRow = (int) Math.floor(rowCount * 0.3);
                    endRow = Math.min(rowCount, (int) Math.ceil(rowCount * 0.7));
                    if (endRow <= startRow) {
                        startRow = 0;
                        endRow = rowCount;
                    }
                }
            }

            // 行扫描顺序：中间 → 离中心行近的优先；靠后 → 从后往前；靠前/靠边 → 从前往后
            List<Integer> rows = new ArrayList<>();
            for (int r = startRow; r < endRow; r++) {
                rows.add(r);
            }
            int centerRow = rowCount / 2;
            if ("中间".equals(z)) {
                rows.sort(Comparator.comparingInt(r -> Math.abs(r - centerRow)));
            } else if ("靠后".equals(z)) {
                rows.sort(Collections.reverseOrder());
            }

            // 影厅正中心列（物理格编号 1..colCount 的中点）
            double midCol = colCount / 2.0;
            List<Long> best = new ArrayList<>();
            double bestColDist = Double.MAX_VALUE;
            for (int r : rows) {
                JSONArray row = grid.getJSONArray(r);
                if (row == null || row.size() < count) {
                    continue;
                }
                // 收集该行可用座位
                List<JSONObject> available = new ArrayList<>();
                for (int c = 0; c < row.size(); c++) {
                    Object cell = row.get(c);
                    if (cell == null) {
                        continue;
                    }
                    JSONObject seat = (JSONObject) cell;
                    if (SeatStatusEnum.AVAILABLE.getValue().equals(seat.getStr("status"))) {
                        available.add(seat);
                    }
                }
                if (available.size() < count) {
                    continue;
                }
                // 找 count 个相邻（colNum 连续）的可用座位：
                //   中间/靠前/靠后 → 优先中心列（列号最接近影厅正中的连续块，避免选到边边）
                //   靠边           → 保持原逻辑从左往右选第一个连续块
                if ("靠边".equals(z)) {
                    List<Long> edge = findConsecutive(available, count);
                    if (!edge.isEmpty()) {
                        return edge;
                    }
                    continue;
                }
                CenterBlock cb = findCenterBlock(available, count, midCol);
                if (cb == null || cb.ids.isEmpty()) {
                    continue;
                }
                // 行按偏好顺序扫描，只保留中心列更靠中的块；距离相等时先扫到的行优先
                if (cb.centerDist < bestColDist - 1e-9) {
                    best = cb.ids;
                    bestColDist = cb.centerDist;
                }
            }
            return best;
        } catch (Exception e) {
            log.warn("自动选座失败: zone={}, count={}", zone, count, e);
        }
        return picked;
    }

    /** 选中的连续座位块（记录其中心列到影厅正中的距离） */
    private record CenterBlock(List<Long> ids, double centerDist) {
    }

    /** 在按列升序排列的可用座位里找 count 个相邻座位 */
    private List<Long> findConsecutive(List<JSONObject> available, int count) {
        List<JSONObject> sorted = new ArrayList<>(available);
        sorted.sort((a, b) -> Integer.compare(
                a.getInt("colNum", 0), b.getInt("colNum", 0)));
        for (int i = 0; i <= sorted.size() - count; i++) {
            boolean consecutive = true;
            for (int j = 1; j < count; j++) {
                int prev = sorted.get(i + j - 1).getInt("colNum", 0);
                int cur = sorted.get(i + j).getInt("colNum", 0);
                if (cur - prev != 1) {
                    consecutive = false;
                    break;
                }
            }
            if (consecutive) {
                List<Long> ids = new ArrayList<>();
                for (int j = 0; j < count; j++) {
                    Long sid = sorted.get(i + j).getLong("seatId");
                    if (sid != null) {
                        ids.add(sid);
                    }
                }
                if (ids.size() == count) {
                    return ids;
                }
            }
        }
        return new ArrayList<>();
    }

    /** 在该行可用座位里找 count 个相邻座位，返回中心列最接近影厅正中的连续块 */
    private CenterBlock findCenterBlock(List<JSONObject> available, int count, double midCol) {
        List<JSONObject> sorted = new ArrayList<>(available);
        sorted.sort((a, b) -> Integer.compare(
                a.getInt("colNum", 0), b.getInt("colNum", 0)));
        List<Long> best = new ArrayList<>();
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i <= sorted.size() - count; i++) {
            boolean consecutive = true;
            for (int j = 1; j < count; j++) {
                int prev = sorted.get(i + j - 1).getInt("colNum", 0);
                int cur = sorted.get(i + j).getInt("colNum", 0);
                if (cur - prev != 1) {
                    consecutive = false;
                    break;
                }
            }
            if (!consecutive) {
                continue;
            }
            int firstCol = sorted.get(i).getInt("colNum", 0);
            int lastCol = sorted.get(i + count - 1).getInt("colNum", 0);
            double centerDist = Math.abs((firstCol + lastCol) / 2.0 - midCol);
            if (centerDist < bestDist - 1e-9) {
                List<Long> ids = new ArrayList<>();
                for (int j = 0; j < count; j++) {
                    Long sid = sorted.get(i + j).getLong("seatId");
                    if (sid != null) {
                        ids.add(sid);
                    }
                }
                if (ids.size() == count) {
                    bestDist = centerDist;
                    best = ids;
                }
            }
        }
        return new CenterBlock(best, bestDist);
    }

    /**
     * 根据座位标签（如 "1排1座"）从座位图中查找对应的 seatId。
     */
    private List<Long> resolveSeatLabels(String seatMapJson, List<String> seatLabels) {
        List<Long> ids = new ArrayList<>();
        try {
            JSONObject map = JSONUtil.parseObj(seatMapJson);
            JSONArray grid = map.getJSONArray("seatGrid");
            if (grid == null || grid.isEmpty()) return ids;

            java.util.Set<String> targetLabels = new java.util.HashSet<>(seatLabels);
            for (int r = 0; r < grid.size(); r++) {
                JSONArray row = grid.getJSONArray(r);
                if (row == null) continue;
                for (int c = 0; c < row.size(); c++) {
                    JSONObject seat = row.getJSONObject(c);
                    if (seat != null && targetLabels.contains(seat.getStr("seatLabel"))) {
                        Long sid = seat.getLong("seatId");
                        if (sid != null) ids.add(sid);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("resolveSeatLabels 失败: labels={}", seatLabels, e);
        }
        return ids;
    }

    private boolean has(String s) {
        return s != null && !s.isEmpty();
    }
}
