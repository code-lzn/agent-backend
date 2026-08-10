package com.limou.agent.ai.movie.graph;

import cn.hutool.json.JSONUtil;
import com.limou.agent.ai.graph.CompiledGraph;
import com.limou.agent.ai.graph.StateGraph;
import com.limou.agent.ai.movie.*;
import com.limou.agent.ai.movie.graph.nodes.*;
import com.limou.agent.ai.movie.tools.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 电影票 Agent StateGraph 工作流
 * <p>
 * 使用声明式图编排：定义节点 → 定义边 → 编译 → 执行。
 * 架构: 代码控流程 + LLM 做意图/回复。
 * <p>
 * GuardRail 安全检查已提前到 SmartMovieRouter 层，图内不再重复检查。
 * ConversationState 由 IntentClassifyNode 加载后挂到 graph state 上，
 * 后续节点通过 state.getConvState() 透传获取，不再各自从 Redis 读取。
 * <p>
 * 图结构:
 * <pre>
 *   START → intent_classify ──┬── search_film ──── END
 *                              ├── search_cinema ── END
 *                              ├── search_schedule  END
 *                              ├── get_seat_map ─── END
 *                              ├── lock_seats -create_order ───── END
 *                              ├──  ─── END
 *                              ├── pay_order ────── END
 *                              └── (default) ────── END
 * </pre>
 * <p>
 * 与 ReAct 模式的区别: LLM 不持有工具，由 Graph 根据意图精确路由到具体工具。
 *   START → intent_classify ──┬── search_film ──── END
 *                              ├── search_cinema ── END
 *                              ├── search_nearby ── END
 *                              ├── search_schedule── END
 *                              ├── get_seat_map ─── END
 *                              ├── lock_seats ──┬── create_order ── END  ← 唯一的两跳
 *                              │                └── END (失败)
 *                              ├── pay_order ────── END
 *                              ├── query_order ──── END
 *                              └── (greeting/chat)─ END (直通，无工具)
 */
@Slf4j
@Component
public class MovieGraphWorkflow {

    // ===== 基础设施 =====

    @Resource
    private MovieStateManager movieStateManager;

    @Resource
    private GraphIntentClassifier graphIntentClassifier;

    // ===== 工具实例 =====

    @Resource
    private SearchFilmsTool searchFilmsTool;

    @Resource
    private SearchCinemasTool searchCinemasTool;

    @Resource
    private SearchNearbyCinemasTool searchNearbyCinemasTool;

    @Resource
    private SearchSchedulesTool searchSchedulesTool;

    @Resource
    private GetSeatMapTool getSeatMapTool;

    @Resource
    private LockSeatsTool lockSeatsTool;

    @Resource
    private CreateOrderTool createOrderTool;

    @Resource
    private PayOrderTool payOrderTool;

    @Resource
    private QueryOrderTool queryOrderTool;

    /** 编译后的图实例（单例，线程安全——每次 invoke 创建新 state） */
    private CompiledGraph<MovieGraphState> graph;

    /**
     * 构建并编译图
     */
    @PostConstruct
    public void init() {
        graph = new StateGraph<MovieGraphState>()

                // ==================== 节点 ====================
                .addNode("intent_classify", new IntentClassifyNode(graphIntentClassifier, movieStateManager))
                .addNode("search_film",     new SearchFilmNode(searchFilmsTool, movieStateManager))
                .addNode("search_cinema",   new SearchCinemaNode(searchCinemasTool, movieStateManager))
                .addNode("search_nearby",   new SearchNearbyNode(searchNearbyCinemasTool, movieStateManager))
                .addNode("search_schedule", new SearchScheduleNode(searchSchedulesTool, movieStateManager))
                .addNode("get_seat_map",    new GetSeatMapNode(getSeatMapTool))
                .addNode("lock_seats",      new LockSeatsNode(lockSeatsTool, getSeatMapTool, searchSchedulesTool, movieStateManager))
                .addNode("create_order",    new CreateOrderNode(createOrderTool, movieStateManager))
                .addNode("pay_order",       new PayOrderNode(payOrderTool))
                .addNode("query_order",     new QueryOrderNode(queryOrderTool))

                // ==================== 边 ====================

                // 入口 → 意图识别（GuardRail 已在路由层完成，直接进入）
                .addEdge(StateGraph.START, "intent_classify")

                // 意图 → 工具（条件路由：根据 LLM 识别的意图分发到对应工具节点）
                .addConditionalEdges("intent_classify",
                        MovieGraphState::getIntent,
                        Map.of(
                                "search_movie",   "search_film",
                                "search_cinema",  "search_cinema",
                                "search_nearby",  "search_nearby",
                                "search_schedule","search_schedule",
                                "get_seat_map",   "get_seat_map",
                                "lock_seats",     "lock_seats",
                                "create_order",   "create_order",
                                "pay_order",      "pay_order",
                                "query_order",    "query_order"
                        ),
                        StateGraph.END  // greeting / chat / unknown → 直接 END
                )

                // 所有工具执行完 → END
                .addEdge("search_film",    StateGraph.END)
                .addEdge("search_cinema",  StateGraph.END)
                .addEdge("search_nearby",  StateGraph.END)
                .addEdge("search_schedule",StateGraph.END)
                .addEdge("get_seat_map",   StateGraph.END)
                // ★ 锁座成功后自动创建订单（先锁座、再下单一条龙），失败则结束（展示替代座位）
                .addConditionalEdges("lock_seats",
                        MovieGraphState::lockRouteKey,
                        Map.of("success", "create_order"),
                        StateGraph.END)
                .addEdge("create_order",   StateGraph.END)
                .addEdge("pay_order",      StateGraph.END)
                .addEdge("query_order",    StateGraph.END)

                // ==================== 编译 ====================
                .compile();

        log.info("MovieGraphWorkflow 图编译完成，注册节点: {}",
                graph.getNodes().keySet());
    }

    /**
     * 执行 Graph 工作流
     *
     * @param message        用户输入
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @return 工作流决策（含工具执行结果和会话状态）
     */
    public WorkflowDecision execute(String message, String conversationId, Long userId) {
        return execute(message, conversationId, userId, null);
    }

    /**
     * 执行 Graph 工作流，并优先复用智能路由阶段的意图识别结果
     *
     * @param message              用户输入
     * @param conversationId       会话ID
     * @param userId               用户ID
     * @param preclassifiedIntent  SmartRouter 的预分类结果（null 则走 LLM 分类）
     * @return 工作流决策（含工具执行结果和会话状态）
     */
    public WorkflowDecision execute(String message, String conversationId, Long userId,
                                    GraphIntentResult preclassifiedIntent) {
        log.info("GraphWorkflow 开始: conversationId={}, hasPreclassified={}",
                conversationId, preclassifiedIntent != null);

        // 构建初始状态
        MovieGraphState initialState = MovieGraphState.builder()
                .userMessage(message)
                .conversationId(conversationId)
                .userId(userId)
                .preclassifiedIntent(preclassifiedIntent)
                .build();

        // 执行图
        MovieGraphState result;
        try {
            result = graph.invoke(initialState);
        } catch (Exception e) {
            log.error("Graph 执行异常: conversationId={}", conversationId, e);
            return WorkflowDecision.blocked("系统异常，请稍后重试");
        }

        // 构造返回结果（保持与旧接口兼容）
        String intent = result.getIntent();
        String toolResult = result.getToolResult();
        boolean hasTool = toolResult != null && !toolResult.isEmpty();

        // 工具结果 → 卡片映射（前端渲染为可视化卡片，不显示原始 JSON）
        String cardType = null;
        Map<String, Object> cardData = null;
        if (hasTool) {
            // 用实际执行的工具名判断卡片类型：锁座+下单串联后最终 toolName=create_order → 出订单卡片
            String effectiveIntent = result.getToolName() != null ? result.getToolName() : intent;
            cardType = cardTypeForIntent(effectiveIntent, toolResult);
            cardData = parseCardData(toolResult);
        }

        log.info("GraphWorkflow 完成: intent={}, hasToolResult={}, cardType={}, toolResult={}, blocked={}",
                intent, hasTool, cardType,
                toolResult != null && toolResult.length() > 250 ? toolResult.substring(0, 250) + "…" : toolResult,
                result.isBlocked());

        return WorkflowDecision.builder()
                .blocked(result.isBlocked())
                .blockMessage(result.getBlockMessage())
                .intent(intent)
                .toolResult(toolResult)
                .toolName(result.getToolName())
                .convState(result.getConvState())
                .stateJson(result.getStateJson())
                .cardType(cardType)
                .cardData(cardData)
                .build();
    }

    // ==================== 卡片映射 ====================

    /** intent → 前端 cardType 映射，lock_seats 根据结果区分成功/失败 */
    private static String cardTypeForIntent(String intent, String toolResult) {
        if (intent == null) return null;
        return switch (intent) {
            case "search_movie"   -> "film_list";
            case "search_cinema"  -> "cinema_list";
            case "search_nearby"  -> "cinema_list";
            case "search_schedule"-> "schedule_list";
            case "get_seat_map"   -> "seat_map";
            case "lock_seats"     -> isSuccessResult(toolResult) ? "seats_confirmed" : "seat_alternatives";
            case "create_order"   -> "order_detail";
            case "pay_order"      -> "payment_form";
            default               -> null;
        };
    }

    /** 判断工具结果是否成功 */
    private static boolean isSuccessResult(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) return false;
        try {
            return JSONUtil.parseObj(toolResult).getBool("success", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析工具结果 JSON 为 Map（供前端卡片渲染） */
    private static Map<String, Object> parseCardData(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) return null;
        try {
            // JSONObject extends HashMap, 直接 new LinkedHashMap 包装即可
            return new LinkedHashMap<>(JSONUtil.parseObj(toolResult));
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", toolResult);
            return fallback;
        }
    }
}
