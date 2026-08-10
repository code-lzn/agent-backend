package com.limou.agent.ai.movie.graph.nodes;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.limou.agent.ai.graph.GraphNode;
import com.limou.agent.ai.movie.MovieStateManager;
import com.limou.agent.ai.movie.graph.MovieGraphState;
import com.limou.agent.ai.movie.graph.MovieIntent;
import com.limou.agent.ai.movie.tools.GetSeatMapTool;
import com.limou.agent.ai.movie.tools.SearchCinemasTool;
import com.limou.agent.ai.movie.tools.SearchSchedulesTool;
import com.limou.agent.model.dto.movie.ConversationState;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalTime;

/**
 * 搜索场次节点
 */
@Slf4j
public class SearchScheduleNode implements GraphNode<MovieGraphState> {

    private final SearchSchedulesTool tool;
    private final SearchCinemasTool cinemaTool;
    private final GetSeatMapTool seatMapTool;
    private final MovieStateManager stateManager;

    public SearchScheduleNode(SearchSchedulesTool tool, SearchCinemasTool cinemaTool,
                              GetSeatMapTool seatMapTool, MovieStateManager stateManager) {
        this.tool = tool;
        this.cinemaTool = cinemaTool;
        this.seatMapTool = seatMapTool;
        this.stateManager = stateManager;
    }

    @Override
    public MovieGraphState execute(MovieGraphState state) {
        if (state.isBlocked()) {
            return state;
        }

        ConversationState convState = state.getConvState();

        // ★ 安全网：filmId 未解析时不能查场次（否则会查出全部场次）
        if (convState.getFilmId() == null) {
            String error = "{\"error\":\"请先确认影片\"}";
            state.setToolResult(error);
            state.setToolName(MovieIntent.SEARCH_SCHEDULE.getCode());
            log.warn("SearchSchedule 被阻止: filmId=null, conversationId={}",
                    state.getConversationId());
            return state;
        }

        // ★ 影院名有值但未解析到 ID：Graph 模式确定性路由下，节点返回错误即结束、
        //   不会像 ReAct 那样让 LLM 回头再调 searchCinemas，必须在这里自动解析，
        //   否则卡片会拿到空结果（前端显示"暂无场次"）。
        if (convState.getCinemaId() == null && has(convState.getCinemaName())) {
            String cinemaErr = resolveCinemaId(convState, state.getConversationId());
            if (cinemaErr != null) {
                state.setToolResult(cinemaErr);
                state.setToolName(MovieIntent.SEARCH_SCHEDULE.getCode());
                log.warn("SearchSchedule 影院解析失败: conversationId={}, err={}",
                        state.getConversationId(), cinemaErr);
                return state;
            }
        }

        String result = tool.searchSchedules(
                convState.getFilmId(),
                convState.getCinemaId(),
                convState.getShowDate(),
                convState.getHallType(),
                convState.getStartTime(),
                null);  // hallName 由 LLM 从用户输入提取后直接传入工具，不从 state 取

        state.setToolResult(result);
        state.setToolName(MovieIntent.SEARCH_SCHEDULE.getCode());

        // 写回解析到的场次；若用户已指定具体时间且唯一命中该场次 → 直接升级展示座位图
        boolean upgradeToSeatMap = persistResolvedSchedule(result, convState, state.getConversationId());
        if (upgradeToSeatMap && convState.getScheduleId() != null) {
            String seatMapJson = seatMapTool.getSeatMap(convState.getScheduleId());
            state.setToolResult(seatMapJson);
            state.setToolName(MovieIntent.GET_SEAT_MAP.getCode());
            state.setIntent(MovieIntent.GET_SEAT_MAP.getCode());
            log.info("SearchSchedule 升级为座位图: scheduleId={}, conversationId={}",
                    convState.getScheduleId(), state.getConversationId());
        }
        log.info("SearchSchedule 完成: conversationId={}", state.getConversationId());
        return state;
    }

    /**
     * 用 SearchCinemas 把影院名解析为 cinemaId 并写回 state。
     *
     * @return 解析失败时的错误 JSON（直接作为 toolResult 返回给 LLM），成功返回 null
     */
    private String resolveCinemaId(ConversationState convState, String conversationId) {
        try {
            String json = cinemaTool.searchCinemas(convState.getCinemaName(), null, convState.getFilmId());
            JSONArray cinemas = JSONUtil.parseObj(json).getJSONArray("cinemas");
            if (cinemas == null || cinemas.isEmpty()) {
                return "{\"error\":\"未找到影院[" + convState.getCinemaName() + "]有该影片的排片\","
                        + "\"cinemaName\":\"" + convState.getCinemaName() + "\","
                        + "\"diagnosis\":\"cinema_not_found\"}";
            }
            if (cinemas.size() > 1) {
                return "{\"error\":\"匹配到多家影院，请确认具体是哪一家\","
                        + "\"cinemaName\":\"" + convState.getCinemaName() + "\","
                        + "\"diagnosis\":\"cinema_ambiguous\"}";
            }
            JSONObject only = cinemas.getJSONObject(0);
            Long cinemaId = only.getLong("cinemaId");
            if (cinemaId == null) {
                return "{\"error\":\"影院解析异常，请重试\",\"diagnosis\":\"cinema_parse_failed\"}";
            }
            convState.setCinemaId(cinemaId);
            convState.setCinemaName(only.getStr("name"));
            stateManager.saveState(conversationId, convState);
            log.info("SearchSchedule 影院名解析: '{}' -> cinemaId={}, conversationId={}",
                    convState.getCinemaName(), cinemaId, conversationId);
            return null;
        } catch (Exception e) {
            log.warn("SearchSchedule 影院解析异常: conversationId={}", conversationId, e);
            return "{\"error\":\"影院解析失败\",\"diagnosis\":\"cinema_parse_failed\"}";
        }
    }

    /**
     * 解析场次结果并写回 scheduleId 到 state。
     *
     * @return 是否应升级展示座位图：用户指定了期望时间（startTime）且唯一命中该时间的场次
     */
    private boolean persistResolvedSchedule(String result, ConversationState convState, String conversationId) {
        try {
            JSONArray sessions = JSONUtil.parseObj(result).getJSONArray("sessions");
            if (sessions == null || sessions.isEmpty()) return false;

            // ★ 影院信息沉淀：即使未选中具体场次，只要本次搜索结果同属一家影院，就写回 cinemaId/cinemaName。
            //   解决"同意 AI 推荐的影院 + 选时间"场景（如用户看完推荐说"看16点的吧"）：
            //   影院名不来自用户输入，若不沉淀，下一轮 cinemaId 为空会全库搜场次、且卡片再按影院分组展示。
            persistUniqueCinema(sessions, convState, conversationId);

            JSONObject selected = null;
            boolean selectedByTime = false;
            String requestedTime = convState.getStartTime();
            if (requestedTime != null && !requestedTime.isBlank()) {
                for (int i = 0; i < sessions.size(); i++) {
                    JSONObject session = sessions.getJSONObject(i);
                    if (!sameTime(requestedTime, session.getStr("startTime"))) continue;
                    if (selected != null) return false; // 多个同时段场次 → 不写回，避免误选
                    selected = session;
                    selectedByTime = true;
                }
            }
            // ★ 文字选场次：按厅型/厅名/影院名匹配唯一场次写回（用户"选IMAX厅""选第二个"等）
            if (selected == null && has(convState.getHallType())) {
                selected = matchUnique(sessions, "hallType", convState.getHallType());
            }
            if (selected == null && has(convState.getHallName())) {
                selected = matchUnique(sessions, "hallName", convState.getHallName());
            }
            if (selected == null && has(convState.getCinemaName())) {
                selected = matchUnique(sessions, "cinemaName", convState.getCinemaName());
            }
            if (selected == null && sessions.size() == 1) {
                selected = sessions.getJSONObject(0);
            }

            if (selected == null || selected.getLong("scheduleId") == null) return false;
            convState.setScheduleId(selected.getLong("scheduleId"));
            convState.setHallName(selected.getStr("hallName"));
            convState.setShowDate(selected.getStr("showDate", convState.getShowDate()));
            convState.setStartTime(selected.getStr("startTime", convState.getStartTime()));
            // 补全 filmId/filmName：解决选场次后 state 里 filmId=null 的问题
            if (selected.getLong("filmId") != null && convState.getFilmId() == null) {
                convState.setFilmId(selected.getLong("filmId"));
            }
            if (selected.getStr("cinemaId") != null && convState.getCinemaId() == null) {
                convState.setCinemaId(selected.getLong("cinemaId"));
            }
            if (selected.getStr("cinemaName") != null && convState.getCinemaName() == null) {
                convState.setCinemaName(selected.getStr("cinemaName"));
            }
            stateManager.saveState(conversationId, convState);
            log.info("SearchSchedule 写回 scheduleId={}, filmId={}: conversationId={}",
                    convState.getScheduleId(), convState.getFilmId(), conversationId);
            return selectedByTime;
        } catch (Exception e) {
            log.warn("SearchSchedule 结果解析失败，跳过场次写回: conversationId={}", conversationId, e);
            return false;
        }
    }

    /**
     * 影院信息沉淀：搜索结果非空且全部场次同属一家影院时，写回 cinemaId/cinemaName。
     * 仅在当前 state 影院为空时生效，避免覆盖用户已确认/新输入的影院。
     * 多影院结果（用户在全城搜场次）不沉淀，避免误绑。
     */
    private void persistUniqueCinema(JSONArray sessions, ConversationState convState, String conversationId) {
        try {
            if (sessions == null || sessions.isEmpty()) return;
            if (convState.getCinemaId() != null) return; // 已有影院，不覆盖
            Long uniqueCinemaId = null;
            String uniqueCinemaName = null;
            for (int i = 0; i < sessions.size(); i++) {
                JSONObject session = sessions.getJSONObject(i);
                Long cid = session.getLong("cinemaId");
                if (cid == null) return;
                if (uniqueCinemaId == null) {
                    uniqueCinemaId = cid;
                    uniqueCinemaName = session.getStr("cinemaName");
                } else if (!uniqueCinemaId.equals(cid)) {
                    return; // 多家影院 → 不沉淀，避免误绑
                }
            }
            if (uniqueCinemaId != null) {
                convState.setCinemaId(uniqueCinemaId);
                if (uniqueCinemaName != null) convState.setCinemaName(uniqueCinemaName);
                stateManager.saveState(conversationId, convState);
                log.info("SearchSchedule 影院沉淀: cinemaId={}, name={}, conversationId={}",
                        uniqueCinemaId, uniqueCinemaName, conversationId);
            }
        } catch (Exception e) {
            log.warn("SearchSchedule 影院沉淀失败: conversationId={}", conversationId, e);
        }
    }

    private boolean sameTime(String requestedTime, String actualTime) {
        if (actualTime == null || actualTime.isBlank()) return false;
        try {
            LocalTime req = LocalTime.parse(requestedTime);
            LocalTime act = LocalTime.parse(actualTime);
            if (req.equals(act)) return true;
            // ★ 整点宽松：用户说"16点"→ LLM 提取 16:00，但场次是 16:12（16:xx 唯一场次）
            //   此时视为命中，避免因分钟不一致而漏选
            if (req.getMinute() == 0 && req.getHour() == act.getHour()) return true;
            return false;
        } catch (Exception ignored) {
            return requestedTime.equals(actualTime);
        }
    }

    /** 按指定字段匹配唯一场次（多个匹配返回 null，不写回避免误选） */
    private JSONObject matchUnique(JSONArray sessions, String field, String value) {
        JSONObject matched = null;
        for (int i = 0; i < sessions.size(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            String v = session.getStr(field);
            if (v != null && (v.equals(value) || v.contains(value) || value.contains(v))) {
                if (matched != null) {
                    return null; // 多个匹配 → 不确定用户选哪个，不写回
                }
                matched = session;
            }
        }
        return matched;
    }

    private boolean has(String s) {
        return s != null && !s.isEmpty();
    }
}
