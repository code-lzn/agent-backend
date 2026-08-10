package com.limou.agent.ai.movie.tools;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limou.agent.ai.movie.ConversationContext;
import com.limou.agent.ai.movie.MovieStateManager;
import com.limou.agent.ai.movie.fuzzy.FuzzyMatch;
import com.limou.agent.ai.movie.fuzzy.FuzzyMatchService;
import com.limou.agent.mapper.CinemaMapper;
import com.limou.agent.mapper.ScheduleMapper;
import com.limou.agent.model.dto.movie.ConversationState;
import com.limou.agent.model.entity.Cinema;
import com.limou.agent.model.entity.Schedule;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 影院搜索工具
 * 支持按名称、城市筛选，可根据影片ID查找有排片的影院
 */
@Slf4j
@Component
public class SearchCinemasTool extends BaseTool {

    @Resource
    private CinemaMapper cinemaMapper;

    @Resource
    private ScheduleMapper scheduleMapper;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private MovieStateManager stateManager;

    @Resource
    private FuzzyMatchService fuzzyMatchService;

    @Tool(description = "搜索影院，支持按名称关键词和城市筛选。可传入filmId查找有该片排片的影院。返回影院列表JSON")
    public String searchCinemas(
            @ToolParam(description = "影院名称关键词（可选）") String keyword,
            @ToolParam(description = "城市（可选）") String city,
            @ToolParam(description = "影片ID，传入则只返回有该片排片的影院（可选）", required = false) Long filmId
    ) {
        try {
            List<Cinema> cinemas;
            // 每家影院该影片的未来可选场次数（filmId 分支统计，用于优先推荐排片充足的影院）
            Map<Long, Long> countByCinema;
            // 模糊纠错命中（SQL like 查空时的拼音/别名兜底）
            FuzzyMatch fuzzyHit = null;

            if (filmId != null) {
                // 通过排片表查找有该影片"未来可售"场次（showDate >= 今天）的影院ID
                // ★ 只算今天及以后的场次，排除只排过过去场次的影院（避免推荐"曾排过但现已无排片"的影院）
                QueryWrapper scheduleWrapper = QueryWrapper.create()
                        .select(Schedule::getCinemaId)
                        .eq(Schedule::getFilmId, filmId)
                        .eq(Schedule::getStatus, "published")
                        .ge(Schedule::getShowDate, Date.valueOf(LocalDate.now()));

                List<Schedule> schedules = scheduleMapper.selectListByQuery(scheduleWrapper);
                countByCinema = schedules.stream()
                        .filter(s -> s.getCinemaId() != null)
                        .collect(Collectors.groupingBy(Schedule::getCinemaId, Collectors.counting()));
                Set<Long> cinemaIds = countByCinema.keySet();

                if (cinemaIds.isEmpty()) {
                    return "{\"cinemas\":[],\"total\":0,\"message\":\"暂无影院排片该影片\"}";
                }

                QueryWrapper cinemaWrapper = QueryWrapper.create()
                        .in(Cinema::getId, cinemaIds)
                        .eq(Cinema::getStatus, "published");

                if (keyword != null && !keyword.isBlank()) {
                    cinemaWrapper.like(Cinema::getName, keyword);
                }
                if (city != null && !city.isBlank()) {
                    cinemaWrapper.eq(Cinema::getCity, city);
                }

                cinemas = cinemaMapper.selectListByQuery(cinemaWrapper);
                // ★ 模糊纠错：该片排影院子集内拼音兜底（如"耀莱"→洛阳耀莱成龙国际影城）
                if (cinemas.isEmpty() && keyword != null && !keyword.isBlank()) {
                    java.util.Optional<FuzzyMatch> fm = fuzzyMatchService.matchCinemaWithin(cinemaIds, keyword);
                    if (fm.isPresent() && fm.get().matchedId() != null) {
                        Cinema matched = cinemaMapper.selectOneById(fm.get().matchedId());
                        if (matched != null) {
                            cinemas = java.util.List.of(matched);
                            fuzzyHit = fm.get();
                            log.info("SearchCinemas 模糊纠错(按片排片子集): '{}' -> '{}' (置信度 {}, source {})",
                                    keyword, fuzzyHit.matchedName(), fuzzyHit.confidence(), fuzzyHit.source());
                        }
                    }
                }
            } else {
                countByCinema = Collections.emptyMap();
                QueryWrapper wrapper = QueryWrapper.create()
                        .eq(Cinema::getStatus, "published");

                if (keyword != null && !keyword.isBlank()) {
                    wrapper.like(Cinema::getName, keyword);
                }
                if (city != null && !city.isBlank()) {
                    wrapper.eq(Cinema::getCity, city);
                }

                wrapper.limit(20);
                cinemas = cinemaMapper.selectListByQuery(wrapper);
                // ★ 模糊纠错：全部已发布影院拼音/别名兜底（如"耀莱"→洛阳耀莱成龙国际影城）
                if (cinemas.isEmpty() && keyword != null && !keyword.isBlank()) {
                    java.util.Optional<FuzzyMatch> fm = fuzzyMatchService.matchCinema(keyword);
                    if (fm.isPresent() && fm.get().matchedId() != null) {
                        Cinema matched = cinemaMapper.selectOneById(fm.get().matchedId());
                        if (matched != null) {
                            cinemas = java.util.List.of(matched);
                            fuzzyHit = fm.get();
                            log.info("SearchCinemas 模糊纠错: '{}' -> '{}' (置信度 {}, source {})",
                                    keyword, fuzzyHit.matchedName(), fuzzyHit.confidence(), fuzzyHit.source());
                        }
                    }
                }
            }

            // ★ 按该影片未来场次数降序排序，优先推荐排片充足的影院
            cinemas.sort(Comparator.comparingLong(
                    c -> -countByCinema.getOrDefault(c.getId(), 0L)));

            List<Map<String, Object>> cinemaList = cinemas.stream().map(c -> {
                Map<String, Object> map = new HashMap<>();
                map.put("cinemaId", c.getId());
                map.put("name", c.getName());
                map.put("address", c.getAddress());
                map.put("city", c.getCity());
                map.put("phone", c.getPhone());
                map.put("businessHours", c.getBusinessHours());
                map.put("tags", c.getTags());
                map.put("basePrice", c.getBasePrice());
                // 该影院该影片未来可选场次数（优先推荐排片充足的影院）
                map.put("futureScheduleCount", countByCinema.getOrDefault(c.getId(), 0L));
                return map;
            }).collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("cinemas", cinemaList);
            result.put("total", cinemaList.size());
            // 模糊纠错信息（可选展示；不影响既有结构）
            if (fuzzyHit != null) {
                result.put("correctedName", fuzzyHit.matchedName());
                result.put("fuzzy", true);
                result.put("fuzzyConfidence", fuzzyHit.confidence());
            }

            // ★ ReAct 模式下写回 cinemaId 到 ConversationState
            String convId = ConversationContext.get();
            if (convId != null && cinemaList.size() == 1) {
                try {
                    ConversationState convState = stateManager.getState(convId);
                    if (convState.getCinemaId() == null) {
                        Map<String, Object> only = cinemaList.get(0);
                        convState.setCinemaId(((Number) only.get("cinemaId")).longValue());
                        convState.setCinemaName((String) only.get("name"));
                        stateManager.saveState(convId, convState);
                        log.info("SearchCinemas 写回 cinemaId={}: convId={}", convState.getCinemaId(), convId);
                    }
                } catch (Exception e) {
                    log.warn("SearchCinemas 写回状态失败: convId={}", convId, e);
                }
            }

            String json = objectMapper.writeValueAsString(result);
            log.info("searchCinemas 查询结果: keyword={}, city={}, filmId={}, 找到{}家影院",
                    keyword, city, filmId, cinemaList.size());
            return json;

        } catch (Exception e) {
            log.error("searchCinemas 查询失败", e);
            return "{\"cinemas\":[],\"total\":0,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @Override
    public String getToolName() {
        return "searchCinemas";
    }

    @Override
    public String getDisplayName() {
        return "搜索影院";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String keyword = arguments.getStr("keyword");
        String city = arguments.getStr("city");
        return String.format("[工具调用] 搜索影院 keyword=%s city=%s", keyword, city);
    }
}
