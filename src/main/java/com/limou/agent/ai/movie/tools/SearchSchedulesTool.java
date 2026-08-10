package com.limou.agent.ai.movie.tools;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limou.agent.ai.movie.fuzzy.FuzzyMatchService;
import com.limou.agent.mapper.CinemaMapper;
import com.limou.agent.mapper.HallMapper;
import com.limou.agent.mapper.ScheduleMapper;
import com.limou.agent.mapper.SeatMapper;
import com.limou.agent.model.entity.Cinema;
import com.limou.agent.model.entity.Hall;
import com.limou.agent.model.entity.Schedule;
import com.limou.agent.model.entity.Seat;
import com.limou.agent.model.enums.SeatStatusEnum;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 场次搜索工具
 * 按影片、影院、日期、厅型搜索放映场次
 */
@Slf4j
@Component
public class SearchSchedulesTool extends BaseTool {

    @Resource
    private ScheduleMapper scheduleMapper;

    @Resource
    private HallMapper hallMapper;

    @Resource
    private SeatMapper seatMapper;

    @Resource
    private CinemaMapper cinemaMapper;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private FuzzyMatchService fuzzyMatchService;

    @Tool(description = "搜索放映场次，需传入影片ID。可选影院ID、日期、开始时间（HH:mm）、厅型、厅名。返回场次列表JSON，含影厅名、时间、价格、余座数")
    public String searchSchedules(
            @ToolParam(description = "影片ID（必填）") Long filmId,
            @ToolParam(description = "影院ID（可选）", required = false) Long cinemaId,
            @ToolParam(description = "放映日期 yyyy-MM-dd（可选）") String showDate,
            @ToolParam(description = "厅型偏好，只能传类型简称如 IMAX/杜比/普通/4DX/VIP/巨幕/激光。不要传影厅全名如"+"杜比全景声厅"+"（传"+"杜比"+"即可）", required = false) String hallType,
            @ToolParam(description = "期望的开始时间 HH:mm，如 14:00。传入后只返回该时间前后3小时内的场次，并按时间接近程度排序（可选）", required = false) String startTime,
            @ToolParam(description = "影厅名称或厅号关键词，如 杜比全景声厅/LED厅。**重要**：用户提到厅号时（如"+"1号厅"+"、"+""+"3号杜比厅"+"），必须传入厅号关键词（如"+"1号"+"、"+""+"3号"+"），用于精确筛选。与厅型可同时传入（非必填）", required = false) String hallName
    ) {
        try {
            // ★ 兜底：如果 LLM 没传 hallName，但 hallType 里包含了厅号信息（如 "2号杜比厅"），自动提取
            if ((hallName == null || hallName.isBlank()) && hallType != null && !hallType.isBlank()) {
                hallName = extractHallNumber(hallType);
                if (hallName != null) {
                    // 从 hallType 中剥离厅号，只保留纯类型码
                    hallType = hallType.replaceAll("\\d+号|\\d+厅|厅$", "").trim();
                    if (hallType.isBlank()) hallType = null;
                    log.info("searchSchedules 兜底提取: hallName={}, hallType={}", hallName, hallType);
                }
            }

            // ★ 厅型归一化：别名/拼音纠正（如"度比"→"杜比"、"巨目"→"巨幕"）
            if (hallType != null && !hallType.isBlank()) {
                String normalized = fuzzyMatchService.normalizeHallType(hallType);
                if (normalized != null && !normalized.equalsIgnoreCase(hallType)) {
                    log.info("searchSchedules 厅型纠错: '{}' -> '{}'", hallType, normalized);
                    hallType = normalized;
                }
            }

            // 解析目标时间
            LocalTime targetTime = null;
            if (startTime != null && !startTime.isBlank()) {
                try {
                    targetTime = LocalTime.parse(startTime.trim());
                } catch (Exception e) {
                    log.warn("开始时间格式错误: {}", startTime);
                }
            }
            // 1. 查询场次
            QueryWrapper wrapper = QueryWrapper.create()
                    .eq(Schedule::getFilmId, filmId)
                    .eq(Schedule::getStatus, "published");

            if (cinemaId != null) {
                wrapper.eq(Schedule::getCinemaId, cinemaId);
            }
            if (showDate != null && !showDate.isBlank()) {
                wrapper.eq(Schedule::getShowDate, Date.valueOf(showDate));
            }
            // hallType 过滤需要在查询 hall 表后进行

            wrapper.orderBy(Schedule::getShowDate, true)
                    .orderBy(Schedule::getStartTime, true);

            List<Schedule> schedules = scheduleMapper.selectListByQuery(wrapper);

            // ★ 过滤已过时的场次（今天已开场的场次不再展示）
            LocalDateTime now = LocalDateTime.now();
            schedules = schedules.stream()
                    .filter(s -> {
                        if (s.getShowDate() == null || s.getStartTime() == null) return true;
                        try {
                            LocalDateTime showDateTime = LocalDateTime.of(
                                    s.getShowDate().toLocalDate(),
                                    LocalTime.parse(s.getStartTime().toString()));
                            return showDateTime.isAfter(now);
                        } catch (Exception e) {
                            return true;
                        }
                    })
                    .collect(Collectors.toList());

            // 2. 查询关联的影厅信息
            Set<Long> hallIds = schedules.stream()
                    .map(Schedule::getHallId)
                    .collect(Collectors.toSet());

            Map<Long, Hall> hallMap = new HashMap<>();
            if (!hallIds.isEmpty()) {
                QueryWrapper hallWrapper = QueryWrapper.create()
                        .in(Hall::getId, hallIds);
                List<Hall> halls = hallMapper.selectListByQuery(hallWrapper);
                for (Hall hall : halls) {
                    hallMap.put(hall.getId(), hall);
                }
            }

            // 影院信息（卡片/AI 需要展示影院名）
            Set<Long> cinemaIds = schedules.stream()
                    .map(Schedule::getCinemaId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<Long, Cinema> cinemaMap = new HashMap<>();
            if (!cinemaIds.isEmpty()) {
                List<Cinema> cinemas = cinemaMapper.selectListByQuery(
                        QueryWrapper.create().in(Cinema::getId, cinemaIds));
                for (Cinema cinema : cinemas) {
                    cinemaMap.put(cinema.getId(), cinema);
                }
            }

            // 3. 构建返回结果（含余座统计）
            List<Map<String, Object>> sessionList = new ArrayList<>();
            for (Schedule s : schedules) {
                Hall hall = hallMap.get(s.getHallId());
                if (hall == null) continue;

                // ★ 厅型过滤 —— 双向模糊匹配，兼容简称和全名
                // LLM 可能传类型简称 "杜比"，也可能传厅名全称 "杜比全景声厅"
                if (hallType != null && !hallType.isBlank()) {
                    String input = hallType.trim();
                    String dbType = hall.getHallType();   // "杜比" / "IMAX"
                    String dbName = hall.getName();        // "杜比全景声厅" / "IMAX激光厅"
                    boolean hasHallName = hallName != null && !hallName.isBlank();
                    boolean matched;
                    if (hasHallName) {
                        // 有厅号/厅名时，hallType 只匹配类型码，不做厅名兜底
                        // 避免 "杜比" 匹配到所有含"杜比"的厅名，覆盖掉厅号的精确筛选
                        matched = dbType != null && (dbType.contains(input) || input.contains(dbType));
                    } else {
                        // 无厅号时兼容双向模糊匹配（LLM 可能传全名如"杜比全景声厅"）
                        matched = (dbType != null && (dbType.contains(input) || input.contains(dbType)))
                                || (dbName != null && (dbName.contains(input) || input.contains(dbName)));
                    }
                    // 拼音兜底：同音错字（如"巨目"→"巨幕"、"度比"→"杜比"）
                    if (!matched) {
                        matched = fuzzyMatchService.hallTypePinyinMatch(input, dbType, dbName);
                    }
                    if (!matched) {
                        continue;
                    }
                }

                // ★ 厅名过滤 —— 精确筛选特定影厅
                // 厅号（如"2号"）使用数字边界匹配，避免"2号"误匹配"12号"
                if (hallName != null && !hallName.isBlank()) {
                    String input = hallName.trim();
                    String dbName = hall.getName();
                    if (dbName == null) {
                        continue;
                    }
                    // 保留原数字厅号边界匹配；拼音只作同音错字兜底（如"请侣厅"→"情侣厅"）
                    if (!hallNameMatches(input, dbName) && !fuzzyMatchService.hallNamePinyinMatch(input, dbName)) {
                        continue;
                    }
                }

                // ★ 时间距离计算 —— 不再硬过滤（"下午→14:00"±3小时窗口会漏掉合理场次），仅用于排序
                long timeDiffMinutes = 0;
                if (targetTime != null && s.getStartTime() != null) {
                    try {
                        LocalTime scheduleTime = LocalTime.parse(s.getStartTime());
                        timeDiffMinutes = Math.abs(ChronoUnit.MINUTES.between(targetTime, scheduleTime));
                    } catch (Exception ignored) {}
                }

                // 统计可用座位数
                long availableSeats = seatMapper.selectCountByQuery(
                        QueryWrapper.create()
                                .eq(Seat::getScheduleId, s.getId())
                                .eq(Seat::getStatus, SeatStatusEnum.AVAILABLE.getValue())
                );

                // 统计总座位数
                long totalSeats = seatMapper.selectCountByQuery(
                        QueryWrapper.create()
                                .eq(Seat::getScheduleId, s.getId())
                );

                Map<String, Object> map = new HashMap<>();
                map.put("scheduleId", s.getId().toString()); // 雪花 ID 用字符串，避免前端精度丢失
                map.put("filmId", String.valueOf(s.getFilmId()));
                map.put("cinemaId", String.valueOf(s.getCinemaId()));
                Cinema cinema = cinemaMap.get(s.getCinemaId());
                map.put("cinemaName", cinema != null ? cinema.getName() : null);
                map.put("hallId", String.valueOf(s.getHallId()));
                map.put("hallName", hall.getName());
                map.put("hallType", hall.getHallType());
                map.put("showDate", s.getShowDate() != null ? s.getShowDate().toString() : null);
                map.put("startTime", s.getStartTime() != null ? s.getStartTime().toString() : null);
                map.put("endTime", s.getEndTime() != null ? s.getEndTime().toString() : null);
                map.put("price", s.getPrice());
                map.put("vipPrice", s.getVipPrice());
                map.put("availableSeats", availableSeats);
                map.put("totalSeats", totalSeats);
                // 时间距离（分钟），供排序使用
                if (targetTime != null) {
                    map.put("timeDiffMinutes", timeDiffMinutes);
                }
                sessionList.add(map);
            }

            // ★ 按时间接近程度排序（有目标时间时）
            if (targetTime != null) {
                sessionList.sort(Comparator.comparingLong(
                        m -> ((Number) m.getOrDefault("timeDiffMinutes", 0L)).longValue()));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("sessions", sessionList);
            result.put("total", sessionList.size());

            // ★ 诊断信息：帮助 LLM 识别"查不到是因为缺影院"的情况
            if (cinemaId == null && sessionList.isEmpty() && filmId != null) {
                result.put("diagnosis", "cinema_not_specified");
                result.put("diagnosisHint", "未指定影院ID，已搜索全部影院的场次但未找到匹配结果。建议引导用户先选择影院再搜索。");
            }

            String json = objectMapper.writeValueAsString(result);
            log.info("searchSchedules 查询结果: filmId={}, cinemaId={}, showDate={}, hallType={}, startTime={}, 找到{}个场次",
                    filmId, cinemaId, showDate, hallType, startTime, sessionList.size());
            return json;

        } catch (Exception e) {
            log.error("searchSchedules 查询失败", e);
            return "{\"sessions\":[],\"total\":0,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 从字符串中提取厅号（如 "2号杜比厅" → "2号"，"1号IMAX" → "1号"）
     * 找不到则返回 null
     */
    private String extractHallNumber(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = Pattern.compile("(\\d+号)").matcher(s);
        if (m.find()) return m.group(1);
        m = Pattern.compile("(\\d+厅)").matcher(s);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 厅名匹配：数字厅号使用边界匹配，防止 "2号" 误匹配 "12号"；
     * 非数字厅名（如"激光"）使用双向 contains。
     */
    private boolean hallNameMatches(String input, String dbName) {
        // 提取输入中的数字部分（如 "2号" → "2", "3号厅" → "3"）
        String numPart = input.replaceAll("[^\\d]", "");
        if (!numPart.isEmpty() && (input.contains("号") || input.contains("厅"))) {
            // 数字厅号：用正则边界匹配，避免 substring 误伤
            // (?<!\d)2号 — "2号" 前面不能是数字（排除 "12号"）
            Pattern p = Pattern.compile("(?<!\\d)" + Pattern.quote(numPart) + "号");
            if (p.matcher(dbName).find()) {
                return true;
            }
            Pattern p2 = Pattern.compile("(?<!\\d)" + Pattern.quote(numPart) + "厅");
            if (p2.matcher(dbName).find()) {
                return true;
            }
            // 也允许输入是完整厅名（如 "2号杜比厅"）
            return input.length() > numPart.length() + 1 && dbName.contains(input);
        }
        // 非数字厅名：双向 contains
        return dbName.contains(input) || input.contains(dbName);
    }

    @Override
    public String getToolName() {
        return "searchSchedules";
    }

    @Override
    public String getDisplayName() {
        return "搜索场次";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        Long filmId = arguments.getLong("filmId");
        return String.format("[工具调用] 搜索场次 filmId=%d", filmId);
    }
}
