package com.limou.agent.ai.movie.graph.nodes;

import com.limou.agent.ai.graph.GraphNode;
import com.limou.agent.ai.movie.MovieStateManager;
import com.limou.agent.ai.movie.graph.GraphIntentClassifier;
import com.limou.agent.ai.movie.graph.GraphIntentResult;
import com.limou.agent.ai.movie.graph.MovieGraphState;
import com.limou.agent.model.dto.movie.ConversationState;
import lombok.extern.slf4j.Slf4j;

/**
 * 意图识别节点
 * <p>
 * 调用 LLM 进行意图分类 + 槽位提取。
 * 加载并合并 ConversationState 后挂到 graph state 上，
 * 后续节点直接通过 state.getConvState() 获取，不再各自读 Redis。
 */
@Slf4j
public class IntentClassifyNode implements GraphNode<MovieGraphState> {

    private final GraphIntentClassifier classifier;
    private final MovieStateManager stateManager;

    public IntentClassifyNode(GraphIntentClassifier classifier, MovieStateManager stateManager) {
        this.classifier = classifier;
        this.stateManager = stateManager;
    }

    @Override
    public MovieGraphState execute(MovieGraphState state) {
        if (state.isBlocked()) {
            return state;
        }

        // 从 Redis 加载会话状态（仅此一次）
        ConversationState convState = stateManager.getState(state.getConversationId());
        if (state.getUserId() != null) {
            convState.setUserId(state.getUserId());
        }

        GraphIntentResult intentResult;

        // 优先复用 SmartRouter 的预分类结果
        if (state.getPreclassifiedIntent() != null) {
            intentResult = state.getPreclassifiedIntent();
            log.info("IntentClassify 复用预分类: conversationId={}, intent={}",
                    state.getConversationId(), intentResult.getIntent());
        } else {
            intentResult = classifier.classify(state.getUserMessage(), convState);
            log.info("IntentClassify LLM 分类: conversationId={}, intent={}",
                    state.getConversationId(), intentResult.getIntent());
        }

        log.info("IntentClassify 已加载状态: conversationId={}, scheduleId={}, orderId={}, seats={}",
                state.getConversationId(), convState.getScheduleId(), convState.getOrderId(),
                convState.getSeatLabels());
        // 写入意图（供条件边路由使用）
        state.setIntent(intentResult.getIntent());

        // 合并槽位到 Redis 状态，然后持久化
        if (intentResult.getSlots() != null) {
            convState = stateManager.mergeState(state.getConversationId(), intentResult.getSlots());
        }
        // mergeState 内部重新从 Redis 加载会覆盖掉之前设的 userId，必须在 merge 之后补设
        convState.setUserId(state.getUserId());

        // ★ 查询类意图（用户重新看影院/场次/影片）→ 作废上一单残留的订单/座位，避免误锁座/误下单
        //   （如上一单下单后 state 残留 seatIds/orderId，用户又说"去XX影院"时会被误当成锁座下单）
        String rawIntent = intentResult.getIntent();
        if ("search_schedule".equals(rawIntent) || "search_cinema".equals(rawIntent)
                || "search_nearby".equals(rawIntent) || "search_movie".equals(rawIntent)) {
            if (convState.getOrderId() != null
                    || (convState.getSeatIds() != null && !convState.getSeatIds().isEmpty())) {
                convState.setOrderId(null);
                convState.setSeatIds(null);
                convState.setSeatLabels(null);
                log.info("IntentClassify 查询意图清残留订单/座位: conversationId={}",
                        state.getConversationId());
            }
        }

        stateManager.saveState(state.getConversationId(), convState);

        // ★ 智能重定向：缺失前置条件时自动降级意图
        String resolvedIntent = resolveIntent(intentResult.getIntent(), convState,
                state.getConversationId(), state.getUserMessage());
        if (!resolvedIntent.equals(intentResult.getIntent())) {
            log.info("IntentClassify 重定向: {} -> {} (conversationId={})",
                    intentResult.getIntent(), resolvedIntent, state.getConversationId());
            state.setIntent(resolvedIntent);
        }

        // 挂到 graph state 上，后续节点直接透传，不再从 Redis 重复读取
        state.setConvState(convState);

        return state;
    }

    /**
     * 智能重定向：当用户意图需要的前置条件不满足时，自动沿订票链降级到上一步。
     *
     * <h3>订票链条（每一步依赖前一步的输出）</h3>
     * <pre>
     *   search_movie → search_schedule → get_seat_map → lock_seats → create_order → pay_order
     *       ↑               ↑                ↑              ↑             ↑            ↑
     *      需要 filmName   需要 filmId      需要 scheduleId 需要 scheduleId+seatIds  需要 orderId
     * </pre>
     * 任一层级缺失前置条件时，自动回退到能补齐缺失信息的步骤。
     */
    private String resolveIntent(String intent, ConversationState state, String conversationId, String userMessage) {
        return switch (intent) {
            // ──────── 层级4: 展示座位图 ────────
            case "get_seat_map" -> {
                // ★ 缺场次
                if (state.getScheduleId() == null) {
                    // ★ 用户明确委托（"帮我订"）且 filmId 存在 → 直接走 lock_seats，
                    //    LockSeatsNode 内置 resolveScheduleId() 自动解析场次，无需降级到 search_schedule
                    if (state.canAutoPickSeats(userMessage) && state.getFilmId() != null) {
                        yield "lock_seats";
                    }
                    if (state.getFilmId() != null) {
                        yield "search_schedule";  // 有影片 → 查场次
                    }
                    if (has(state.getFilmName())) {
                        yield "search_movie";  // 有影片名 → 先搜影片
                    }
                    yield "chat";  // 什么都没有 → 追问
                }
                // 用户明确要求自动选座 → 升级为 lock_seats 自动锁座下单
                if (state.canAutoPickSeats(userMessage)) {
                    yield "lock_seats";
                }
                yield intent;
            }
            // ──────── 层级2: 查场次 ────────
            case "search_schedule" -> {
                if (state.getFilmId() == null) {
                    if (has(state.getFilmName())) {
                        yield "search_movie";  // 有影片名 → 先搜影片
                    }
                    yield "chat";  // 什么都没有 → 追问
                }
                yield intent;
            }
            // ──────── 层级5: 锁座 ────────
            // LockSeatsNode 内置 resolveScheduleId()，有 filmId 即可自动解析场次，无需重定向
            case "lock_seats" -> {
                if (state.getScheduleId() == null && state.getFilmId() == null) {
                    if (has(state.getFilmName())) {
                        yield "search_movie";
                    }
                    yield "chat";
                }
                // 缺座位、未指定具体座位、也不允许自动选座 → 展示座位图让用户手动选
                // ★ 用户已给出具体座位标签（如"2排3和4"→ seatLabels 非空）时不降级，
                //   直接走 LockSeatsNode 的 resolveSeatLabels 真正锁座（否则座位被丢弃，第二轮会换成自动选座的座位）
                boolean hasSeatLabels = state.getSeatLabels() != null && !state.getSeatLabels().isEmpty();
                if ((state.getSeatIds() == null || state.getSeatIds().isEmpty())
                        && !hasSeatLabels
                        && !state.canAutoPickSeats(userMessage)) {
                    yield "get_seat_map";
                }
                yield intent;
            }
            // ──────── 层级6: 下单 ────────
            // CreateOrderNode 依赖 LockSeatsNode 串联，缺 scheduleId/seatIds 时升级到 lock_seats
            case "create_order" -> {
                if (state.getScheduleId() == null && state.getFilmId() == null) {
                    if (has(state.getFilmName())) {
                        yield "search_movie";
                    }
                    yield "chat";
                }
                if (state.getSeatIds() == null || state.getSeatIds().isEmpty()) {
                    yield "lock_seats"; // LockSeatsNode 自动解析场次 + 锁座
                }
                yield intent;
            }
            // ──────── 层级7: 支付 ────────
            case "pay_order" -> {
                if (state.getOrderId() == null) {
                    yield "chat";  // 没有订单号 → 无法支付，追问用户
                }
                yield intent;
            }
            // ──────── 查询订单 ────────
            case "query_order" -> {
                if (state.getOrderId() == null) {
                    yield "chat";  // 没有订单号 → 无法查询，追问用户
                }
                yield intent;
            }
            default -> intent;
        };
    }

    private boolean has(String s) {
        return s != null && !s.isEmpty();
    }
}