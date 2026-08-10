package com.limou.agent.ai.movie.graph;

import com.limou.agent.ai.movie.GuardRailResult;
import com.limou.agent.ai.movie.MovieGuardRail;
import com.limou.agent.model.dto.movie.ConversationState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 智能路由器
 * 混合策略（规则 + LLM）判断用户消息应该走 ReAct 还是 Graph
 *
 * ReAct: 用户一句话提供足够信息 → LLM 自主链式调工具，一次完成
 * Graph: 用户信息不足/闲聊 → 代码控流程逐步引导
 */
@Slf4j
@Component
public class SmartMovieRouter {

    /** 一条消息里同时出现"订/买/购" + 以下至少 2 类关键词 → 直接 ReAct */
    private static final Pattern BOOKING_KEYWORD = Pattern.compile("(订|买|购|下单|抢|选座|选.{0,2}座位|要|预约|预定).*(票|座|位)");
    /** 影片 + 影院 + 座位诉求 → 即使没有显式订票词也走 ReAct（如"想看志愿3在万达，给我座位表"） */
    private static final Pattern SEAT_REQUEST_HINT = Pattern.compile("(座|位|选.{0,2}座|座.{0,2}表|座.{0,2}图)");
    private static final Pattern MOVIE_NAME_HINT = Pattern.compile(
            "《.+》|流浪地球|封神|哪吒|长安|万里|满江红|热辣|飞驰|人生|八角笼|孤注一掷|消失的她"
                    + "|志愿|战狼|红海|唐探|捉妖|大圣|白蛇|姜子牙|深海|熊出没|长津湖|水门桥|狙击手|奇迹|独行月球"
                    + "|蜘蛛侠|阿凡达|复仇者|钢铁侠|变形金刚|速度与激情|哈利波特|指环王|黑客帝国|星际穿越|盗梦空间|敦刻尔克|奥本海默"
                    + "|\\S{2,4}[：:]\\S");
    private static final Pattern TIME_HINT = Pattern.compile("(今天|明天|后天|周[一二三四五六日]|上午|下午|晚上|凌晨|\\d+点|\\d+:\\d+)");
    private static final Pattern CINEMA_HINT = Pattern
            .compile("(影院|影城|万达|CGV|金逸|中影|大地|博纳|卢米埃|百老汇|英皇|UA|离.{0,5}近|附近|旁边)");
    /** ★ 多部影片 + 下单：如"看A和B，都帮我选中间的票，直接下单" */
    private static final Pattern MULTI_MOVIE_BOOKING = Pattern.compile(
            "(看|想看).{0,20}(和|跟|与|还有).{0,20}(看|电影|影片)"
                    + "|(都|全|每).{0,5}(帮|给).{0,5}(选|买|订|下单)");
    /** ★ "直接下单" / "帮我下单" + 已有上下文 → 跳过搜索直接锁座 */
    private static final Pattern DIRECT_ORDER = Pattern.compile(
            "(直接|马上|立刻|现在).{0,3}(下单|买|订|支付)"
                    + "|(帮|给).{0,5}(下单|买票|订票)");
    /** ★ 通用影片名模式：中文名+数字后缀（如"XX3""XX2"） */
    private static final Pattern MOVIE_NAME_SUFFIX = Pattern.compile(
            "[\\u4e00-\\u9fa5]{2,6}\\d{1,2}(?!\\d)");
    private static final Pattern GREETING_PATTERN = Pattern.compile("^(你好|hi|hello|嗨|在吗|在不在|嘿|哈喽|早上好|下午好|晚上好)[!！。.]*$");
    private static final Pattern CANCEL_PATTERN = Pattern.compile("(算了|不买了|取消|不要了|放弃|改天|下次)");
    private static final Pattern NEARBY_CINEMA_PATTERN = Pattern.compile(
            "(附近|周边|本地|离我近).{0,8}(影院|影城)|(影院|影城).{0,8}(附近|周边|有哪些)");
    /** ★ 纯地理位置名 — 用户没说影院品牌，而是说了一个地点/地标名，应走附近搜索 */
    private static final Pattern LOCATION_NAME_PATTERN = Pattern.compile(
            "(大学|学院|学校|校区|广场|商场|购物中心|百货|火车站|高铁站|机场|地铁站|汽车站|公交站"
                    + "|小区|社区|花园|公寓|写字楼|大厦|中心|医院|体育馆|图书馆|公园|景区|乐园|古城|古镇"
                    + "|街|路|大道|商圈|步行街|小吃街|美食街|万达广场|大悦城|万象城|银泰|来福士|吾悦)");
    private static final Pattern MOVIE_DISCOVERY_PATTERN = Pattern.compile(
            "(推荐|最近|现在|当前).{0,8}(电影|影片|热映|新片)|(热映|新片).{0,8}(哪些|推荐|电影)");
    /** 用户问"我在哪里""定位"→ REACT，调用 locateUser 工具 */
    private static final Pattern LOCATE_MYSELF_PATTERN = Pattern.compile(
            "(我|我人|我当前|我目前|我这边).{0,10}(在|位于|位置|定位|坐标|在.{0,5}哪)"
                    + "|(定位|位置|坐标|地址).{0,5}(我|在哪|查询|一下)"
                    + "|(这是|我现在).{0,5}(什么|哪个|哪)");
    /** "附近有什么"、"周围有好吃的吗"—— 基础定位查询，需要先定位再搜索 */
    private static final Pattern AROUND_ME_PATTERN = Pattern.compile(
            "(附近|周边|周围|本地|这里).{0,5}(有|能|可以|什么|哪些|哪里|好玩|好吃)");
    /** 清晰的定位问题：我在哪 */
    private static final Pattern DIRECT_LOCATE_PATTERN = Pattern.compile(
            "我在哪|我的位置|我现在的位置|当前位置|查询位置|定位$|定位我|我在什么地方");

    @Resource
    private GraphIntentClassifier intentClassifier;

    @Resource
    private MovieGuardRail guardRail;

    /**
     * 路由决策
     *
     * @param message 用户输入
     * @param state   当前会话状态（含历史槽位）
     * @return REACT 或 GRAPH
     */
    public SmartRouteResult route(String message, ConversationState state) {
        // === GuardRail 安全检查（提前到路由层，避免下层重复执行） ===
        GuardRailResult guardResult = guardRail.check(message);
        if (!guardResult.allowed()) {
            log.info("Router: GuardRail 拦截 → BLOCKED");
            return SmartRouteResult.blocked(guardResult.message());
        }

        // === 规则层（零延迟） ===

        if (matches(GREETING_PATTERN, message)) {
            log.info("Router: 规则命中 问候 → GRAPH");
            return SmartRouteResult.graph(directIntent("greeting"));
        }

        if (matches(CANCEL_PATTERN, message)) {
            log.info("Router: 规则命中 取消 → GRAPH");
            return SmartRouteResult.graph(directIntent("chat"));
        }

        if (matches(NEARBY_CINEMA_PATTERN, message)) {
            log.info("Router: 规则命中 附近影院 → REACT（使用 Amap 地理搜索）");
            return SmartRouteResult.react();
        }

        // ★ 纯地理位置名（大学/广场/火车站等），不含影院品牌 → search_nearby
        if (matches(LOCATION_NAME_PATTERN, message) && !matches(CINEMA_HINT, message)) {
            log.info("Router: 规则命中 地理位置名 → GRAPH (search_nearby)");
            ConversationState locSlots = new ConversationState();
            locSlots.setCinemaName(message.trim()); // 作为地理位置描述传入
            return SmartRouteResult.graph(GraphIntentResult.builder()
                    .intent("search_nearby")
                    .slots(locSlots)
                    .build());
        }

        if (matches(MOVIE_DISCOVERY_PATTERN, message)) {
            log.info("Router: 规则命中 影片发现 → GRAPH");
            return SmartRouteResult.graph(directIntent("search_movie"));
        }

        // ★ 包场/全包 → GRAPH 确定性锁座下单（LockSeatsNode 自动选全场所有可用座位）
        if (message.contains("包场") || message.contains("全包") || message.contains("整个厅")) {
            log.info("Router: 规则命中 包场 → GRAPH (全场锁座下单)");
            return SmartRouteResult.graph(null);
        }

        // ★ 已有上下文 → 强制 Graph（维护多轮对话状态）
        if (hasExistingContext(state)) {
            log.info("Router: 已有上下文(film={}, cinema={}, schedule={}) → GRAPH",
                    state.getFilmId(), state.getCinemaId(), state.getScheduleId());
            return SmartRouteResult.graph(null);
        }

        // ★ 多部影片 + 下单（如"看A和B，都帮我选中间的票，直接下单"）→ REACT
        if (matches(MULTI_MOVIE_BOOKING, message)
                && (matches(BOOKING_KEYWORD, message) || matches(DIRECT_ORDER, message))) {
            log.info("Router: 规则命中 多部影片+下单 → REACT");
            return SmartRouteResult.react();
        }

        // ★ 通用影片名+数字后缀（如"流浪地球3""封神2"）+ 购票 → REACT
        if (matches(MOVIE_NAME_SUFFIX, message) && matches(BOOKING_KEYWORD, message)
                && (matches(TIME_HINT, message) || matches(CINEMA_HINT, message))) {
            log.info("Router: 规则命中 影片名+数字后缀+购票 → REACT");
            return SmartRouteResult.react();
        }

        // ★ 直接下单（有上下文时直接走锁座链路）→ 无上下文走 Graph
        if (matches(DIRECT_ORDER, message)) {
            if (state != null && state.getFilmId() != null) {
                log.info("Router: 规则命中 直接下单+有影片 → GRAPH (锁座下单)");
                return SmartRouteResult.graph(null);
            }
            log.info("Router: 规则命中 直接下单+无上下文 → REACT");
            return SmartRouteResult.react();
        }

        // ★ 明确购票意图（订/买/购/下单/选座…票座）+ 已有影片 → 走 Graph 的确定性锁座下单链路
        // （Graph 内 LockSeatsNode 自动解析场次 → 自动选座 → 锁座 → 条件边 create_order，一次完成；
        // 避免 ReAct 下 LLM 工具调用不稳定——只锁座停下/重复下单）
        if (matches(BOOKING_KEYWORD, message) && state != null && state.getFilmId() != null) {
            log.info("Router: 购票意图 + 已有影片 → GRAPH (确定性锁座下单)");
            return SmartRouteResult.graph(null);
        }

        // 影片名 + 影院 + 座位诉求 → ReAct（如"想看志愿3在万达，给我座位表"）
        if (matches(MOVIE_NAME_HINT, message)
                && matches(CINEMA_HINT, message)
                && matches(SEAT_REQUEST_HINT, message)) {
            log.info("Router: 规则命中 影片+影院+座位诉求 → REACT");
            return SmartRouteResult.react();
        }

        // 一条消息包含 订票动作 + 影片名 + (时间 或 影院) → ReAct（首次，无上下文）
        if (matches(BOOKING_KEYWORD, message)
                && matches(MOVIE_NAME_HINT, message)
                && (matches(TIME_HINT, message) || matches(CINEMA_HINT, message))) {
            log.info("Router: 规则命中 一句话订票 → REACT");
            return SmartRouteResult.react();
        }

        // ★ 用户问"我在哪里"/"定位"/"附近有什么"→ REACT（调用 locateUser 工具）
        if (matches(DIRECT_LOCATE_PATTERN, message)
                || (matches(LOCATE_MYSELF_PATTERN, message) && !matches(CINEMA_HINT, message))) {
            log.info("Router: 规则命中 定位查询 → REACT (locateUser)");
            return SmartRouteResult.react();
        }
        // "附近有什么" — 先定位再搜索，走 REACT 让 LLM 链式调 locateUser + 周边搜索
        if (matches(AROUND_ME_PATTERN, message) && !matches(CINEMA_HINT, message)) {
            log.info("Router: 规则命中 周边探索 → REACT (locateUser + around)");
            return SmartRouteResult.react();
        }

        // === LLM 层（规则拿不准时） ===
        try {
            GraphIntentResult result = intentClassifier.classify(message, state);
            int totalFilled = countMergedSlots(result.getSlots(), state);

            if (totalFilled >= 4) {
                log.info("Router: LLM 判定 槽位{}/7 ≥4 → REACT", totalFilled);
                return SmartRouteResult.react();
            }

            log.info("Router: LLM 判定 槽位{}/7 <4 → GRAPH", totalFilled);
            return SmartRouteResult.graph(result);

        } catch (Exception e) {
            log.warn("Router: LLM 判定异常，兜底走 Graph", e);
            return SmartRouteResult.graph(directIntent("chat"));
        }
    }

    /**
     * 统计合并后总槽位数
     */
    private int countMergedSlots(ConversationState slots, ConversationState state) {
        if (slots == null)
            //槽位没有提取到使用旧的
            return countStateSlots(state);
        int count = 0;
        if (has(slots.getFilmName()) || (state != null && has(state.getFilmName())))
            count++;
        if (has(slots.getCinemaName()) || (state != null && has(state.getCinemaName())))
            count++;
        if (has(slots.getShowDate()) || has(slots.getStartTime())
                || (state != null && (has(state.getShowDate()) || has(state.getStartTime()))))
            count++;
        if (slots.getTicketCount() != null && slots.getTicketCount() > 0
                || (state != null && state.getTicketCount() != null && state.getTicketCount() > 0))
            count++;
        if (slots.getScheduleId() != null || (state != null && state.getScheduleId() != null))
            count++;
        if (has(slots.getHallType()) || (state != null && has(state.getHallType())))
            count++;
        if (has(slots.getPreferredSeatZone()) || (state != null && has(state.getPreferredSeatZone())))
            count++;
        return count;
    }

    private int countStateSlots(ConversationState state) {
        if (state == null)
            return 0;
        int count = 0;
        if (has(state.getFilmName()))
            count++;
        if (has(state.getCinemaName()))
            count++;
        if (has(state.getShowDate()) || has(state.getStartTime()))
            count++;
        if (state.getTicketCount() != null && state.getTicketCount() > 0)
            count++;
        if (state.getScheduleId() != null)
            count++;
        if (has(state.getHallType()))
            count++;
        if (has(state.getPreferredSeatZone()))
            count++;
        return count;
    }

    private GraphIntentResult directIntent(String intent) {
        return GraphIntentResult.builder()
                .intent(intent)
                .slots(new ConversationState())
                .build();
    }

    /**
     * 判断是否已有实质性的对话上下文——有则走 Graph 维护状态，不走 ReAct。
     */
    private boolean hasExistingContext(ConversationState state) {
        if (state == null)
            return false;
        return (state.getFilmId() != null && state.getCinemaId() != null)
                || state.getScheduleId() != null
                || state.getOrderId() != null;
    }

    private boolean has(String s) {
        return s != null && !s.isEmpty();
    }

    private boolean matches(Pattern p, String s) {
        return p.matcher(s).find();
    }
//    p.matcher(s)：创建一个“匹配器”工具，准备拿规则 p 去扫描字符串 s。
//
//.find()：拿着扫描器从字符串开头到结尾搜索，一旦找到任意一个符合规则的子串，立即返回 true；如果整个字符串扫描完都没找到，返回 false。
}
