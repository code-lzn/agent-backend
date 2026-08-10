package com.limou.agent.ai.movie.graph.nodes;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.limou.agent.ai.graph.GraphNode;
import com.limou.agent.ai.movie.MovieStateManager;
import com.limou.agent.ai.movie.graph.MovieGraphState;
import com.limou.agent.ai.movie.graph.MovieIntent;
import com.limou.agent.ai.movie.tools.SearchCinemasTool;
import com.limou.agent.ai.movie.tools.SearchFilmsTool;
import com.limou.agent.model.dto.movie.ConversationState;
import lombok.extern.slf4j.Slf4j;

/**
 * 搜索影院节点
 */
@Slf4j
public class SearchCinemaNode implements GraphNode<MovieGraphState> {

    private final SearchCinemasTool tool;
    private final SearchFilmsTool filmsTool;
    private final MovieStateManager stateManager;

    public SearchCinemaNode(SearchCinemasTool tool, SearchFilmsTool filmsTool, MovieStateManager stateManager) {
        this.tool = tool;
        this.filmsTool = filmsTool;
        this.stateManager = stateManager;
    }

    @Override
    public MovieGraphState execute(MovieGraphState state) {
        if (state.isBlocked()) {
            return state;
        }

        ConversationState convState = state.getConvState();

        // ★ 用户问"有某片排片的影院"但会话中尚未解析 filmId → 先按影片名解析，
        //    避免 filmId=null 时 searchCinemas fallback 到全量影院（推荐出无排片的影院）
        Long filmId = convState.getFilmId();
        if (filmId == null && convState.getFilmName() != null && !convState.getFilmName().isBlank()) {
            String filmResult = filmsTool.searchFilms(convState.getFilmName(), null, "rating_desc");
            Long resolved = resolveFilmId(filmResult, convState.getFilmName());
            if (resolved == null) {
                // 影片名匹配失败 → 不推荐影院，如实告知
                state.setToolResult("{\"cinemas\":[],\"total\":0,\"message\":\"未找到该影片，无法为您推荐影院\"}");
                state.setToolName(MovieIntent.SEARCH_CINEMA.getCode());
                log.info("SearchCinema 影片解析失败，不推荐影院: conversationId={}, filmName={}",
                        state.getConversationId(), convState.getFilmName());
                return state;
            }
            convState.setFilmId(resolved);
            filmId = resolved;
            stateManager.saveState(state.getConversationId(), convState);
            log.info("SearchCinema 按影片名解析 filmId={}: conversationId={}", resolved, state.getConversationId());
        }

        String result = tool.searchCinemas(
                convState.getCinemaName(),
                convState.getCurrentCity(),
                filmId);

        state.setToolResult(result);
        state.setToolName(MovieIntent.SEARCH_CINEMA.getCode());
        persistResolvedCinema(result, convState, state.getConversationId());
        log.info("SearchCinema 完成: conversationId={}", state.getConversationId());
        return state;
    }

    private void persistResolvedCinema(String result, ConversationState convState, String conversationId) {
        try {
            JSONArray cinemas = JSONUtil.parseObj(result).getJSONArray("cinemas");
            if (cinemas == null || cinemas.isEmpty()) return;

            JSONObject selected = null;
            for (int i = 0; i < cinemas.size(); i++) {
                JSONObject cinema = cinemas.getJSONObject(i);
                if (convState.getCinemaName() != null
                        && convState.getCinemaName().equalsIgnoreCase(cinema.getStr("name"))) {
                    selected = cinema;
                    break;
                }
            }
            if (selected == null && cinemas.size() == 1) selected = cinemas.getJSONObject(0);
            if (selected == null || selected.getLong("cinemaId") == null) return;

            convState.setCinemaId(selected.getLong("cinemaId"));
            convState.setCinemaName(selected.getStr("name", convState.getCinemaName()));
            stateManager.saveState(conversationId, convState);
            log.info("SearchCinema 写回 cinemaId={}: conversationId={}", convState.getCinemaId(), conversationId);
        } catch (Exception e) {
            log.warn("SearchCinema 结果解析失败，跳过影院写回: conversationId={}", conversationId, e);
        }
    }

    /**
     * 从 searchFilms 结果中解析匹配用户所提影片名的 filmId。
     * 复用 SearchFilmNode 的匹配逻辑：精确/包含匹配，唯一结果兜底。
     */
    private Long resolveFilmId(String filmResult, String requestedName) {
        try {
            JSONArray films = JSONUtil.parseObj(filmResult).getJSONArray("films");
            if (films == null || films.isEmpty()) return null;

            String normalized = requestedName.trim();
            for (int i = 0; i < films.size(); i++) {
                JSONObject film = films.getJSONObject(i);
                String dbName = film.getStr("name");
                if (dbName == null) continue;
                if (dbName.equalsIgnoreCase(normalized)
                        || dbName.contains(normalized)
                        || normalized.contains(dbName)) {
                    return film.getLong("filmId");
                }
            }
            if (films.size() == 1) return films.getJSONObject(0).getLong("filmId");
            return null;
        } catch (Exception e) {
            log.warn("SearchCinema 解析影片ID失败: {}", e.getMessage());
            return null;
        }
    }
}
