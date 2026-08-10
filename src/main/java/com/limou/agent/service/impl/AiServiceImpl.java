package com.limou.agent.service.impl;

import cn.hutool.json.JSONUtil;
import com.limou.agent.ai.AiCodeGeneratorFactory;
import com.limou.agent.ai.movie.ConversationContext;
import com.limou.agent.ai.movie.GuardRailResult;
import com.limou.agent.ai.movie.MovieGuardRail;
import com.limou.agent.ai.movie.MovieStateManager;
import com.limou.agent.ai.movie.MovieToolManager;
import com.limou.agent.ai.movie.WorkflowDecision;
import com.limou.agent.ai.movie.graph.MovieGraphWorkflow;
import com.limou.agent.ai.movie.graph.MovieIntent;
import com.limou.agent.ai.movie.graph.GraphResponseGenerator;
import com.limou.agent.ai.movie.graph.GraphIntentClassifier;
import com.limou.agent.ai.movie.graph.GraphIntentResult;
import com.limou.agent.ai.movie.graph.SmartMovieRouter;
import com.limou.agent.ai.movie.graph.SmartRouteResult;
import com.limou.agent.model.entity.ChatHistory;
import com.limou.agent.model.dto.movie.ConversationState;
import com.limou.agent.service.AiService;
import com.limou.agent.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AI 服务实现
 * <p>
 * GuardRail 策略：
 * <ul>
 *   <li>Smart 端点 → SmartMovieRouter 内置 GuardRail → 路由到 reactCore / graphCore（无二次校验）</li>
 *   <li>旧 ReAct 端点 → 入口层 GuardRail → reactCore</li>
 *   <li>旧 Graph 端点 → 入口层 GuardRail → graphCore</li>
 *   <li>旧 POST 端点 → 入口层 GuardRail → ReactAgent</li>
 * </ul>
 * 核心方法（reactCore / graphCore）不再做 GuardRail，避免重复校验。
 */
@Slf4j
@Service
public class AiServiceImpl implements AiService {

    @Resource
    private AiCodeGeneratorFactory aiCodeGeneratorFactory;

    @Resource
    private MovieStateManager movieStateManager;

    @Resource
    private MovieGraphWorkflow movieGraphWorkflow;

    @Resource
    private SmartMovieRouter smartMovieRouter;

    @Resource
    private GraphIntentClassifier intentClassifier;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private MovieToolManager movieToolManager;

    @Resource
    private MovieGuardRail movieGuardRail;

    @Resource
    @Qualifier("movieToolCallbacks")
    private ToolCallback[] movieToolCallbacks;

    @Value("classpath:prompts/movie-agent-prompt.md")
    private org.springframework.core.io.Resource moviePrompt;

    private String movieSystemPromptCache;

    // ==================== 电影票 Agent ====================

    // ---- GuardRail 辅助 ----

    /**
     * 构建 GuardRail 拦截的 SSE 响应流
     */
    private Flux<ServerSentEvent<String>> blockedFlux(String blockMessage, String conversationId) {
        return Flux.just(
                        ServerSentEvent.<String>builder()
                                .data(JSONUtil.toJsonStr(Map.of("d", blockMessage)))
                                .build(),
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build())
                .doFinally(signal -> movieStateManager.refreshTtl(conversationId));
    }

    /**
     * ReAct 核心 —— 无 GuardRail，由调用方保证已校验
     */
    private Flux<ServerSentEvent<String>> reactCore(
            String message, String conversationId, Long userId, String currentCity) {
        log.info("ReAct 流式: conversationId={}, userId={}", conversationId, userId);

        // 注入 conversationId 到 ThreadLocal，让工具方法能写回 ConversationState
        ConversationContext.set(conversationId);

        String prompt = withCurrentCity(getMovieSystemPrompt(), currentCity)
                + "\n\n## 当前用户\n"
                + "当前登录用户ID: " + userId + "\n"
                + "调用 createOrder 和 getUserPreference 时必须使用此 userId，禁止使用其他值。";

        // ★ tool_start 更新指示器 + status 在气泡内显示文字，两者同时展示
        Flux<ServerSentEvent<String>> thinkingFlux = Flux.just(
                ServerSentEvent.<String>builder()
                        .data(JSONUtil.toJsonStr(Map.of(
                                "d", "正在分析您的需求...",
                                "type", "status")))
                        .build(),
                ServerSentEvent.<String>builder()
                        .data(JSONUtil.toJsonStr(Map.of(
                                "d", "正在分析您的需求...",
                                "type", "tool_start",
                                "toolName", "意图识别")))
                        .build()
               );

        // ★ 意图分类 + ReAct 主流程延迟到订阅时执行，确保 thinking 事件先发送
        Flux<ServerSentEvent<String>> mainFlux = Flux.defer(() -> {
            // ★ ReAct 前用意图分类器提取槽位写回状态
            try {
                GraphIntentResult intentResult = intentClassifier.classify(
                        message, movieStateManager.getState(conversationId));
                if (intentResult.getSlots() != null) {
                    movieStateManager.mergeState(conversationId, intentResult.getSlots());
                    log.info("ReAct 前置槽位写回: conversationId={}, intent={}",
                            conversationId, intentResult.getIntent());
                }
            } catch (Exception e) {
                log.warn("ReAct 前置槽位提取失败: conversationId={}", conversationId, e);
            }

            StringBuilder fullResponse = new StringBuilder();
            final List<Map<String, Object>> emittedCards = new ArrayList<>();

            return aiCodeGeneratorFactory.doAgentChatStream(
                            message, conversationId, prompt,
                            movieToolCallbacks, movieToolManager.getToolDisplayNames(), "movie-agent")
                    .map(chunk -> {
                    if ("tool_start".equals(chunk.type())) {
                        String msg = "正在" + chunk.toolDisplayName() + "...";
                        return ServerSentEvent.<String>builder()
                                .data(JSONUtil.toJsonStr(Map.of(
                                        "d", msg,
                                        "type", "tool_start",
                                        "toolName", chunk.toolName())))
                                .build();
                    }
                    if ("card".equals(chunk.type())) {
                        // ★ 工具结果作为卡片事件发送，前端渲染为交互卡片
                        //   同时累积进 emittedCards，保证多张订单卡在历史中都能恢复
                        try {
                            emittedCards.add(Map.of(
                                    "cardType", chunk.cardType(),
                                    "data", JSONUtil.parseObj(chunk.cardData())));
                        } catch (Exception e) {
                            emittedCards.add(Map.of(
                                    "cardType", chunk.cardType(),
                                    "data", Map.of("raw", chunk.cardData())));
                        }
                        Map<String, Object> cardPayload = new LinkedHashMap<>();
                        cardPayload.put("type", "card");
                        cardPayload.put("cardType", chunk.cardType());
                        try {
                            cardPayload.put("data", JSONUtil.parseObj(chunk.cardData()));
                        } catch (Exception e) {
                            cardPayload.put("data", Map.of("raw", chunk.cardData()));
                        }
                        return ServerSentEvent.<String>builder()
                                .data(JSONUtil.toJsonStr(cardPayload))
                                .build();
                    }
                    fullResponse.append(chunk.content());
                    return ServerSentEvent.<String>builder()
                            .data(JSONUtil.toJsonStr(Map.of("d", chunk.content())))
                            .build();
                })
                .concatWith(Mono.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()))
                .onErrorResume(e -> {
                    log.error("ReAct SSE 异常: conversationId={}", conversationId, e);
                    return Flux.just(
                            ServerSentEvent.<String>builder()
                                    .data(JSONUtil.toJsonStr(Map.of("d", "抱歉，出了一点问题：" + e.getMessage())))
                                    .build(),
                            ServerSentEvent.<String>builder()
                                    .event("done")
                                    .data("")
                                    .build());
                })
                .doFinally(signal -> {
                    // ★ 持久化所有卡片：连下多单时每张订单卡都保存，前端历史可聚合展示全部订单
                    saveMovieChatHistory(
                            conversationId, userId, message, fullResponse.toString(),
                            emittedCards);
                    movieStateManager.refreshTtl(conversationId);
                    ConversationContext.clear();
                });
        }); // Flux.defer 结束

        return Flux.concat(thinkingFlux, mainFlux);
    }

    // ---- Graph 模式 ----

    /** intent → 中文工具名映射（由 MovieIntent 枚举统一维护） */
    private static final Map<String, String> INTENT_TOOL_NAMES = MovieIntent.toolDisplayNames();

    /**
     * Graph 核心 —— 无 GuardRail，由调用方保证已校验
     */
    private Flux<ServerSentEvent<String>> graphCore(
            String message, String conversationId, Long userId, GraphIntentResult preclassifiedIntent) {
        log.info("GraphWorkflow 流式: conversationId={}, userId={}", conversationId, userId);

        // 1. Graph 工作流（同步阻塞，offload 到 boundedElastic，避免阻塞 Netty 线程）
        return Mono.fromCallable(() ->
                        movieGraphWorkflow.execute(message, conversationId, userId, preclassifiedIntent))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(decision -> {
                    if (decision.isBlocked()) {
                        return blockedFlux(decision.getBlockMessage(), conversationId);
                    }

        // 2. 准备流式回复
        String intent = decision.getIntent();
        String toolResult = decision.getToolResult();
        boolean hasTool = toolResult != null && !toolResult.isEmpty();
        String rawCardType = decision.getCardType();
        boolean rawHasCard = rawCardType != null && decision.getCardData() != null;

        // ★ 空结果卡片 = 没有实际数据，不应该走卡片路径（否则 LLM 会对着 ConversationState 编造内容）
        final boolean emptyCard = rawHasCard && isCardDataEmpty(decision.getCardData());
        final boolean hasCard = rawHasCard && !emptyCard;
        final String cardType = hasCard ? rawCardType : null;
        final String cardError = emptyCard ? "查询无结果"
                : (rawHasCard ? extractCardError(decision.getCardData()) : null);

        ConversationState state = decision.getConvState() != null
                ? decision.getConvState()
                : movieStateManager.getState(conversationId);

        String stateContext = state.toPromptContext();
        // 有卡片时 {tool_result} 替换为简短提示 + 强约束，避免 LLM 把原始 JSON 当文本输出
        final String toolResultForPrompt;
        final String antiJsonRule;
        if (cardError != null) {
            toolResultForPrompt = "工具执行结果：" + cardError + "。原始数据：" + (toolResult != null ? toolResult : "无");
            antiJsonRule = "\n\n## 重要规则\n工具执行结果为空或失败（" + cardError + "）。必须如实告知用户没有找到匹配结果，**严禁**用对话状态中的历史信息编造"+"找到了XXX"+"之类的虚假回复。引导用户换个条件试试。不要输出JSON。";
        } else if (hasCard) {
            // 卡片数据摘要注入回复上下文：让 LLM 能引用卡片内容（如某场次属于哪家影院/价格/时间），但不原样输出
            String cardSummary = JSONUtil.toJsonStr(decision.getCardData());
            if (cardSummary.length() > 1500) {
                cardSummary = cardSummary.substring(0, 1500) + "…";
            }
            // 存最近卡片摘要到会话状态，供后续轮次引用（"第二个场次"、"这个场次是哪个影院"等指代消解）
            try {
                state.setLastSearchContext(cardSummary);
                movieStateManager.saveState(conversationId, state);
            } catch (Exception e) {
                log.warn("保存卡片摘要到会话状态失败: conversationId={}", conversationId, e);
            }
            toolResultForPrompt = "已通过前端卡片展示。卡片数据摘要（供你引用卡片内容，不要原样输出）：\n" + cardSummary;
            antiJsonRule = "\n\n## 重要规则\n工具执行结果已通过可视化卡片在前端展示，你**不要**重复/原样输出卡片中的原始数据（尤其不要输出JSON）。但你可以根据卡片摘要回答用户关于卡片内容的问题（例如某个场次属于哪家影院、价格、时间、余座等）。只需要用自然语言组织回复。"
                    + cardTypeHint(cardType);
        } else {
            toolResultForPrompt = hasTool ? toolResult : "无工具结果";
            antiJsonRule = "";
        }
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", Locale.CHINA));
        String prompt = (GraphResponseGenerator.RESPONSE_PROMPT + antiJsonRule)
                .replace("{today}", today)
                .replace("{intent}", intent != null ? intent : "chat")
                .replace("{tool_result}", toolResultForPrompt)
                .replace("{state}", stateContext)
                .replace("{input}", message);

        StringBuilder fullResponse = new StringBuilder();

        Flux<ServerSentEvent<String>> responseStream;

        // ★ 前置 thinking 事件：与 reactCore 一致，告知用户正在分析
        Flux<ServerSentEvent<String>> prefixEvents = Flux.just(
                ServerSentEvent.<String>builder()
                        .data(JSONUtil.toJsonStr(Map.of(
                                "d", "正在分析您的需求...",
                                "type", "status")))
                        .build(),
                ServerSentEvent.<String>builder()
                        .data(JSONUtil.toJsonStr(Map.of(
                                "d", "正在分析您的需求...",
                                "type", "tool_start",
                                "toolName", "意图识别")))
                        .build()
        );

        if (hasCard) {
            // 卡片事件：前端渲染为可视化卡片
            Map<String, Object> cardPayload = new LinkedHashMap<>();
            cardPayload.put("type", "card");
            cardPayload.put("cardType", cardType);
            cardPayload.put("data", decision.getCardData());
            prefixEvents = prefixEvents.concatWith(Flux.just(
                    ServerSentEvent.<String>builder()
                            .data(JSONUtil.toJsonStr(cardPayload))
                            .build()));
        } else if (hasTool) {
            // 无卡片时显示旧的 tool_start 提示
            String displayName = INTENT_TOOL_NAMES.getOrDefault(decision.getToolName(), decision.getToolName());
            prefixEvents = prefixEvents.concatWith(Flux.just(
                    ServerSentEvent.<String>builder()
                            .data(JSONUtil.toJsonStr(Map.of(
                                    "d", "正在" + displayName + "...",
                                    "type", "tool_start",
                                    "toolName", decision.getToolName())))
                            .build()));
        }

        // 文本流：LLM 自然语言回复
        Flux<ServerSentEvent<String>> textStream = aiCodeGeneratorFactory
                .doSimpleChatStream(prompt, conversationId)
                .map(chunk -> {
                    fullResponse.append(chunk);
                    return ServerSentEvent.<String>builder()
                            .data(JSONUtil.toJsonStr(Map.of("d", chunk)))
                            .build();
                });

        responseStream = prefixEvents.concatWith(textStream);

        return responseStream
                .concatWith(Mono.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()))
                .onErrorResume(e -> {
                    log.error("Graph SSE 异常: conversationId={}", conversationId, e);
                    return Flux.just(
                            ServerSentEvent.<String>builder()
                                    .data(JSONUtil.toJsonStr(Map.of("d", "抱歉，出了一点问题：" + e.getMessage())))
                                    .build(),
                            ServerSentEvent.<String>builder()
                                    .event("done")
                                    .data("")
                                    .build());
                })
                .doFinally(signal -> {
                    saveMovieChatHistory(
                            conversationId,
                            userId,
                            message,
                            fullResponse.toString(),
                            cardType,
                            decision.getCardData());
                    movieStateManager.refreshTtl(conversationId);
                });
        });  // flatMapMany: offload Graph 阻塞调用到 boundedElastic
    }

    // ---- 智能路由 ----

    @Override
    public Flux<ServerSentEvent<String>> doMovieSmartChatStream(
            String message, String conversationId, Long userId, String currentCity,
            Double lat, Double lng) {
        String normalizedCity = normalizeCity(currentCity);

        Flux<ServerSentEvent<String>> routedStream = Mono.fromCallable(() -> {
                    ConversationState state = movieStateManager.getState(conversationId);
                    if (userId != null) state.setUserId(userId);
                    if (normalizedCity != null) state.setCurrentCity(normalizedCity);
                    // ★ 存储用户精确坐标到对话状态
                    if (lat != null && lat != 0) state.setUserLat(lat);
                    if (lng != null && lng != 0) state.setUserLng(lng);
                    movieStateManager.saveState(conversationId, state);
                    // SmartRouter 内置 GuardRail，一次完成「安全检查 + 路由决策」
                    return smartMovieRouter.route(message, state);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(route -> routeStream(
                        route, message, conversationId, userId, normalizedCity));

        return routedStream.onErrorResume(e -> {
            log.error("SmartStream 异常: conversationId={}", conversationId, e);
            return Flux.just(
                    ServerSentEvent.<String>builder()
                            .data(JSONUtil.toJsonStr(Map.of("d", "抱歉，出了一点问题：" + e.getMessage())))
                            .build(),
                    ServerSentEvent.<String>builder()
                            .event("done")
                            .data("")
                            .build());
        });
    }

    /**
     * 路由分发 —— 内部方法，GuardRail 已在 SmartRouter 层完成
     */
    private Flux<ServerSentEvent<String>> routeStream(
            SmartRouteResult route, String message, String conversationId,
            Long userId, String currentCity) {
        log.info("SmartRouter: decision={}, reusedIntent={}, conversationId={}",
                route.decision(), route.intentResult() != null, conversationId);
        return switch (route.decision()) {
            case REACT -> reactCore(message, conversationId, userId, currentCity);
            case GRAPH -> graphCore(message, conversationId, userId, route.intentResult());
            case BLOCKED -> blockedFlux(route.blockMessage(), conversationId);
        };
    }

    // ==================== 辅助方法 ====================

    private String extractCardError(Map<String, Object> cardData) {
        Object error = cardData.get("error");
        return error != null && !error.toString().isBlank() ? error.toString() : null;
    }

    /**
     * 根据实际展示的卡片类型注入话术约束，防止 LLM 话术与卡片类型脱节
     * （如展示"场次列表"却回复"座位图已展示"——用户想看座位图时尤为致命）。
     * 该约束在 prompt 规则之上做代码级兜底，LLM 即使自由发挥也跳不出。
     */
    private String cardTypeHint(String cardType) {
        if (cardType == null) return "";
        return switch (cardType) {
            case "schedule_list" -> "\n\n**★ 当前展示的是『可选场次列表』卡片（不是座位图！）**\n"
                    + "请在回复中如实说明「已为您列出可选场次」，并引导用户：点击场次卡片即可查看该场次的座位图，"
                    + "或让用户告知想看哪一场（如「看08:00那场」）后再展示座位图。\n"
                    + "**绝对禁止**：说「座位图已展示/已显示在页面上」、让用户「直接点座位图自己选」、"
                    + "或提及「确认选座」按钮——本次展示的是场次列表，不是座位图。"
                    + "即使对话状态里已有票数/影片信息，也不要臆测用户已选好场次。";
            case "seat_map" -> "\n\n**★ 当前展示的是『座位图』卡片**\n"
                    + "可以引导用户在座位图上点选座位，或说明想坐的位置（中间/靠前/靠后）。";
            default -> "";
        };
    }

    /** 判断卡片数据是否为空结果（如 sessions=[], films=[], cinemas=[]） */
    private boolean isCardDataEmpty(Map<String, Object> cardData) {
        if (cardData == null) return true;
        // 检查常见的数据列表字段
        for (String key : new String[]{
                "sessions", "films", "cinemas", "schedules", "alternatives",
                "seatGrid",    // 座位图二维数组
                "lockedSeats", // 锁座结果列表
                "conflictSeats"}) {
            Object val = cardData.get(key);
            if (val instanceof List && !((List<?>) val).isEmpty()) return false;
        }
        // 检查 total / count / availableCount 等数值字段
        for (String key : new String[]{"total", "availableCount", "count", "scheduleId", "hallId", "orderId"}) {
            Object val = cardData.get(key);
            if (val instanceof Number && ((Number) val).intValue() > 0) return false;
        }
        // 有 success=false 或有 error → 不算"空"（走 error 分支处理）
        if (cardData.containsKey("error") || Boolean.FALSE.equals(cardData.get("success"))) return false;
        return true;
    }

    private void saveMovieChatHistory(String conversationId, Long userId, String userMessage, String aiResponse) {
        saveMovieChatHistory(conversationId, userId, userMessage, aiResponse, List.of());
    }

    private void saveMovieChatHistory(
            String conversationId,
            Long userId,
            String userMessage,
            String aiResponse,
            String cardType,
            Map<String, Object> cardData) {
        if (cardType == null || cardData == null) {
            saveMovieChatHistory(conversationId, userId, userMessage, aiResponse, List.of());
        } else {
            saveMovieChatHistory(conversationId, userId, userMessage, aiResponse,
                    List.of(Map.of("cardType", cardType, "data", cardData)));
        }
    }

    /** 持久化对话历史：user 消息 + 若干张卡片（可多张）+ ai 回复，保证连下多单时每张订单卡都能在历史中恢复 */
    private void saveMovieChatHistory(
            String conversationId,
            Long userId,
            String userMessage,
            String aiResponse,
            List<Map<String, Object>> cards) {
        try {
            Long sessionId = Long.valueOf(conversationId);
            chatHistoryService.save(ChatHistory.builder()
                    .sessionId(sessionId).userId(userId)
                    .messageType("user").message(userMessage).build());

            if (cards != null) {
                for (Map<String, Object> card : cards) {
                    Map<String, Object> cardPayload = new LinkedHashMap<>();
                    cardPayload.put("type", "card");
                    cardPayload.put("cardType", card.get("cardType"));
                    cardPayload.put("data", card.get("data"));
                    chatHistoryService.save(ChatHistory.builder()
                            .sessionId(sessionId).userId(userId)
                            .messageType("card").message(JSONUtil.toJsonStr(cardPayload)).build());
                }
            }

            if (aiResponse != null && !aiResponse.isBlank()) {
                chatHistoryService.save(ChatHistory.builder()
                        .sessionId(sessionId).userId(userId)
                        .messageType("ai").message(aiResponse).build());
            }
        } catch (Exception e) {
            log.error("保存电影对话历史失败: conversationId={}", conversationId, e);
        }
    }

    private String normalizeCity(String city) {
        if (city == null || city.isBlank() || "请选择城市".equals(city)) return null;
        String normalized = city.trim();
        return normalized.endsWith("市")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private String withCurrentCity(String prompt, String currentCity) {
        if (currentCity == null) return prompt;
        String ctx = "\n\n## ★ 当前运行上下文（已自动获取，绝对不要反问用户！）\n"
                + "用户当前城市：" + currentCity
                + "。系统已通过 GPS/IP 自动定位，用户说"+"附近"+"时直接调用 searchNearbyCinemas，**严禁**追问"+"您在哪里"+"！";
        // ★ 从 ConversationState 获取精确坐标注入 prompt
        try {
            String convId = ConversationContext.get();
            if (convId != null) {
                ConversationState state = movieStateManager.getState(convId);
                if (state.getUserLat() != null && state.getUserLng() != null
                        && state.getUserLat() != 0 && state.getUserLng() != 0) {
                    ctx += "\n用户精确坐标: lat=" + state.getUserLat() + ", lng=" + state.getUserLng()
                         + "。调用 searchNearbyCinemas 时务必同时传入 lat/lng（radius 建议 2000-3000），不要只传城市名！";
                }
            }
        } catch (Exception ignored) { /* 非关键 */ }
        return prompt + ctx;
    }

    private String getMovieSystemPrompt() {
        if (movieSystemPromptCache == null) {
            try {
                movieSystemPromptCache = moviePrompt.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("读取电影票提示词失败", e);
                movieSystemPromptCache = "你是一个电影票智能助手";
            }
        }
        // 每次调用实时注入当天日期（不能把日期缓存死）
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", Locale.CHINA));
        return movieSystemPromptCache.replace("{today}", today);
    }
}
