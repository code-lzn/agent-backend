package com.limou.agent.ai.movie.graph;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.limou.agent.model.dto.movie.ConversationState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Graph 意图分类器（优化版）
 *
 * 优化：精简 prompt ~90行→~30行 + ChatClient 复用，不额外设置 options
 * （defaultOptions 会覆盖 ChatModel 的 model/api-key 等默认值导致空响应）
 */
@Slf4j
@Component
public class GraphIntentClassifier {

    private static final String INTENT_PROMPT = """
            你是电影票意图识别器。分析用户输入和对话状态，只输出 JSON。

            ## 意图（12 种）
            search_movie | search_cinema | search_nearby | search_schedule | get_seat_map
            | lock_seats | create_order | pay_order | query_order | get_preference | greeting | chat

            ## 区分要点
            - 地点/地标名（大学/广场/火车站/景区→search_nearby，location 填地点名）
            - 影院品牌名（万达/万达影城/CGV→search_cinema，cinemaName 填影院名）
            - 只有出现"买/订/下单/选座/要X张票"才识别为 lock_seats/create_order
            - **只报票数不选座**（如"两位""两张""要2张"），用户没让 AI 选座也没说坐哪 → 识别为 get_seat_map（展示座位图），让用户自己选或下一步说偏好；**不要**识别为 lock_seats/create_order
            - 用户说"包场/全包/包下整个厅/全场" → lock_seats，preferredSeatZone 填 "全场"（系统自动锁全场所有可用座位，ticketCount 填 null）
            - 用户有 orderId 后问"看看订单/订单状态"→query_order
            - 时段换算：上午→09:00 中午→12:00 下午→14:00 晚上→19:00
            - 用户说"都帮我选/全都要/两个都"表示多部影片，filmName 填逗号分隔

            ## 槽位提取规则
            从用户输入中提取以下字段，有则填，无则 null：
            - filmName: 影片名称
            - filmType: 影片类型
            - cinemaName: 影院名称（仅当用户明确说影院品牌名时才填，地点名不要填这里）

            ## 错别字/音近词处理（重要）
            用户可能输入错别字或音近词（如"支柱下"实指"蜘蛛侠"、"巨目"实指"巨幕"、"耀莱"实指"耀莱成龙国际影城"）。
            **仍然按用户原词提取到对应槽位**（filmName/cinemaName/hallType），不要因为名字怪异就漏提或归为 unknown。纠错由搜索工具自动完成。
            - location: 地理位置描述（仅 search_nearby 意图填写）。提取用户想去的地点/地标/机构名，如"河南科技大学"、"万达广场"、"北京西站"。用户只说"附近"且无具体地点时填 null（工具会用城市名定位）
            - hallType: 厅型（IMAX/杜比/VIP/巨幕/4DX/普通 等）。**注意："第X排""前排""后排""中间""靠边"等描述座位位置的词绝不是 hallType，不要填到这里！**
            - showDate: 日期 yyyy-MM-dd
            - startTime: 时间 HH:mm
            - ticketCount: 票数（整数）
            - seatLabels: 座位标签数组，如 ["5排6座"]。用户指定具体座位时填写（"第X排第Y个/座""X排Y座"→["X排Y座"]）
            - scheduleId: 场次ID（整数）
            - orderId: 订单ID（整数）
            - preferredSeatZone: 偏好座位区域（中间/靠前/靠后/第一排/最后一排/靠边/全场）。用户只说区域不说具体座位号时填这里

            ## 选择动作与时段识别（重要）
            - 用户说"选X厅/选X影院/选第几个/就选这个/那个/确认/可以"等选择或确认动作时，务必提取 hallType/hallName/cinemaName，意图识别为 get_seat_map 或 lock_seats
            - "上午/中午/下午/晚上/凌晨"等时段词，换算成 startTime 填入（上午→09:00，中午→12:00，下午→14:00，晚上→19:00），供场次时间过滤

            ## 当前日期
            今天是：{today}（含星期）。用户提到"今天/明天/后天/某月某日/周几/几点/下午/晚上"等相对时间时，据此换算成具体日期填入 showDate/startTime。

            ## 当前对话状态
            {state}

            ## 输出格式（严格JSON，不要markdown包裹）
            {"intent":"search_movie","slots":{"filmName":"影片名","hallType":"IMAX"},"askPrompt":null}

            ## 用户输入
            {input}
            """;

    @Resource
    private DashScopeChatModel dashscopeChatModel;

//    @Resource
//    private DeepSeekChatModel deepSeekChatModel;

    @Resource
    private ObjectMapper objectMapper;

    private ChatClient chatClient;

    /**
     * 意图分类结果缓存 —— 按 "conversationId:消息摘要:状态摘要" 缓存，避免相同输入重复调用 LLM
     * ★ 修复：key 含 conversationId，防止跨会话串数据
     * 过期策略：写入后 5 分钟过期，最多 2000 条
     */
//    private final Cache<String, GraphIntentResult> intentCache = Caffeine.newBuilder()
//            .maximumSize(2000)
//            .expireAfterWrite(Duration.ofMinutes(5))
//            .recordStats()
//            .build();

    @PostConstruct
    public void init() {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
    }

    /**
     * 意图识别 + 槽位提取
     */
    public GraphIntentResult classify(String userMessage, ConversationState currentState) {
        // ★ 优化1：缓存命中直接返回，跳过 LLM 调用（key 含 conversationId，不会跨会话串数据）
        String cacheKey = buildCacheKey(userMessage, currentState);
//        GraphIntentResult cached = intentCache.getIfPresent(cacheKey);
//        if (cached != null) {
//            log.debug("Graph Intent 缓存命中: key={}", cacheKey);
//            return cached;
//        }

        String stateContext = currentState != null ? currentState.toPromptContext() : "无";
        String today = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", Locale.CHINA));
        String prompt = INTENT_PROMPT
                .replace("{today}", today)
                .replace("{state}", stateContext)
                .replace("{input}", userMessage);

        try {
            // ★ 优化2：限制 max_tokens=256 + temperature=0，减少生成时间和 token 消耗
            String raw = chatClient.prompt()
                    .user(prompt)
//                    .options(options -> options
//                            .maxOutputTokens(256)    // 意图 JSON 很小，不需要默认的 4096
//                            .temperature(0.0))        // 分类任务不需要随机性，避免幻觉
                    .call()
                    .content();
            String json = cleanJson(raw);
            Map<String, Object> result = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            String intent = (String) result.getOrDefault("intent", "chat");

            ConversationState slots = new ConversationState();
            @SuppressWarnings("unchecked")
            Map<String, Object> slotsMap = (Map<String, Object>) result.get("slots");
            if (slotsMap != null) {
                if (slotsMap.get("filmName") != null)
                    slots.setFilmName((String) slotsMap.get("filmName"));
                if (slotsMap.get("filmType") != null)
                    slots.setFilmType((String) slotsMap.get("filmType"));
                if ("search_nearby".equals(intent) && slotsMap.get("location") != null) {
                    slots.setCinemaName((String) slotsMap.get("location"));
                } else if (slotsMap.get("cinemaName") != null) {
                    slots.setCinemaName((String) slotsMap.get("cinemaName"));
                }
                if (slotsMap.get("hallType") != null)
                    slots.setHallType((String) slotsMap.get("hallType"));
                if (slotsMap.get("showDate") != null)
                    slots.setShowDate((String) slotsMap.get("showDate"));
                if (slotsMap.get("startTime") != null)
                    slots.setStartTime((String) slotsMap.get("startTime"));
                if (slotsMap.get("ticketCount") != null) {
                    Object tc = slotsMap.get("ticketCount");
                    slots.setTicketCount(tc instanceof Integer ? (Integer) tc : Integer.parseInt(tc.toString()));
                }
                if (slotsMap.get("scheduleId") != null) {
                    Object sid = slotsMap.get("scheduleId");
                    slots.setScheduleId(sid instanceof Long ? (Long) sid : Long.valueOf(sid.toString()));
                }
                if (slotsMap.get("orderId") != null) {
                    Object oid = slotsMap.get("orderId");
                    slots.setOrderId(oid instanceof Long ? (Long) oid : Long.valueOf(oid.toString()));
                }
                if (slotsMap.get("preferredSeatZone") != null)
                    slots.setPreferredSeatZone((String) slotsMap.get("preferredSeatZone"));
            }
            // userId 从当前会话状态获取，不由 LLM 提取（安全：防止用户伪造）
            if (currentState != null && currentState.getUserId() != null) {
                slots.setUserId(currentState.getUserId());
            }
            log.info("Graph Intent: intent={}, slots={}, cacheKey={}", intent, slots, cacheKey);
            GraphIntentResult intentResult = GraphIntentResult.builder()
                    .intent(intent)
                    .slots(slots)
                    .askPrompt((String) result.get("askPrompt"))
                    .build();

            // ★ 写入缓存
//            intentCache.put(cacheKey, intentResult);
            return intentResult;
        } catch (Exception e) {
            log.error("意图识别失败，降级为 chat", e);
            GraphIntentResult fallback = GraphIntentResult.builder()
                    .intent("chat")
                    .slots(new ConversationState())
                    .build();
            // ★ 异常结果也短期缓存，避免重复失败调用（1 分钟）
//            intentCache.put(cacheKey, fallback);
            return fallback;
        }
    }

    /**
     * 构建缓存 key：conversationId + 消息摘要 + 关键槽位变化
     * ★ 修复：key 含 conversationId（取自 state），防止跨会话缓存串数据
     */
    private String buildCacheKey(String userMessage, ConversationState state) {
        String convId = state != null && state.getConversationId() != null
                ? state.getConversationId() : "no-session";
        String msg = userMessage != null ? userMessage.trim() : "";
        String stateKey = "";
        if (state != null) {
            stateKey = (state.getFilmId() != null ? "f" + state.getFilmId() : "")
                    + (state.getCinemaId() != null ? "c" + state.getCinemaId() : "")
                    + (state.getScheduleId() != null ? "s" + state.getScheduleId() : "")
                    + (state.getOrderId() != null ? "o" + state.getOrderId() : "");
        }
        return convId + ":" + msg.hashCode() + ":" + stateKey;
    }

    private String cleanJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        String json = raw.trim();
        if (json.startsWith("```json"))
            json = json.substring(7);
        else if (json.startsWith("```"))
            json = json.substring(3);
        if (json.endsWith("```"))
            json = json.substring(0, json.length() - 3);
        return json.trim();
    }
}
