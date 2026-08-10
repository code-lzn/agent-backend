package com.limou.agent.ai.movie;

import com.limou.agent.mapper.ConversationStateMapper;
import com.limou.agent.model.dto.movie.ConversationState;
import com.limou.agent.model.entity.ConversationStateEntity;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 电影票对话状态管理器
 * 双写 Redis + MySQL，Redis 为主、MySQL 兜底（防重启丢失）
 * Key: movie:state:{conversationId}
 * TTL: 7 天
 */
@Slf4j
@Component
public class MovieStateManager {

    private static final String KEY_PREFIX = "movie:state:";
    private static final Duration STATE_TTL = Duration.ofDays(7);

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ConversationStateMapper conversationStateMapper;

    /**
     * 获取会话状态（Redis → 不存在则从 MySQL 恢复）
     */
    public ConversationState getState(String conversationId) {
        // 1. 先查 Redis
        RBucket<String> bucket = getBucket(conversationId);
        String json = bucket.get();
        if (json != null && !json.isEmpty()) {
            try {
                ConversationState state = ConversationState.fromJson(json);
                state.setConversationId(conversationId);
                log.debug("加载对话状态(Redis): conversationId={}", conversationId);
                return state;
            } catch (Exception e) {
                log.warn("解析对话状态失败: conversationId={}", conversationId, e);
            }
        }

        // 2. Redis 空 → 从 MySQL 恢复
        try {
            ConversationState dbState = loadFromDb(conversationId);
            if (dbState != null) {
                // 恢复到 Redis
                saveToRedis(conversationId, dbState);
                log.info("从 MySQL 恢复对话状态: conversationId={}, filmName={}, cinemaName={}",
                        conversationId, dbState.getFilmName(), dbState.getCinemaName());
                return dbState;
            }
        } catch (Exception e) {
            log.warn("从 MySQL 加载状态失败: conversationId={}", conversationId, e);
        }

        // 3. 都没有 → 新状态
        ConversationState newState = new ConversationState();
        newState.setConversationId(conversationId);
        newState.setLastUpdate(LocalDateTime.now());
        return newState;
    }

    /**
     * 保存会话状态 — Redis + MySQL 双写
     */
    public void saveState(String conversationId, ConversationState state) {
        state.setConversationId(conversationId);
        state.setLastUpdate(LocalDateTime.now());
        // Redis（主存储）
        saveToRedis(conversationId, state);
        // MySQL（兜底，异步保存，静默失败）
        try {
            saveToDb(conversationId, state);
        } catch (Exception e) {
            log.warn("保存状态到 MySQL 失败（状态仍在 Redis 中）: conversationId={}", conversationId, e);
        }
    }

    // ===== 私有方法 =====

    private void saveToRedis(String conversationId, ConversationState state) {
        try {
            RBucket<String> bucket = getBucket(conversationId);
            bucket.set(state.toJson(), STATE_TTL);
        } catch (Exception e) {
            log.error("保存状态到 Redis 失败: conversationId={}", conversationId, e);
        }
    }

    /** 存到 MySQL 独立表 conversation_state（UPSERT：有则更新，无则插入） */
    private void saveToDb(String conversationId, ConversationState state) {
        LocalDateTime now = LocalDateTime.now();
        // 查已有记录
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq(ConversationStateEntity::getConversationId, conversationId);
        ConversationStateEntity existing = conversationStateMapper.selectOneByQuery(queryWrapper);

        if (existing != null) {
            existing.setStateJson(state.toJson());
            existing.setUserId(state.getUserId());
            existing.setUpdateTime(now);
            conversationStateMapper.update(existing);
        } else {
            conversationStateMapper.insert(ConversationStateEntity.builder()
                    .conversationId(conversationId)
                    .stateJson(state.toJson())
                    .userId(state.getUserId())
                    .createTime(now)
                    .updateTime(now)
                    .build());
        }
    }

    /** 查找用户当前活跃会话的 conversationId */
    public String findCurrentConversationId(Long userId) {
        if (userId == null) return null;
        try {
            QueryWrapper wrapper = QueryWrapper.create()
                    .eq(ConversationStateEntity::getUserId, userId)
                    .orderBy(ConversationStateEntity::getUpdateTime, false)
                    .limit(1);
            ConversationStateEntity entity = conversationStateMapper.selectOneByQuery(wrapper);
            return entity != null ? entity.getConversationId() : null;
        } catch (Exception e) {
            log.warn("查找当前会话失败: userId={}", userId, e);
            return null;
        }
    }

    /** 从 MySQL 加载状态 */
    private ConversationState loadFromDb(String conversationId) {
        QueryWrapper wrapper = QueryWrapper.create()
                .eq(ConversationStateEntity::getConversationId, conversationId);
        ConversationStateEntity entity = conversationStateMapper.selectOneByQuery(wrapper);
        if (entity != null && entity.getStateJson() != null && !entity.getStateJson().isBlank()) {
            ConversationState state = ConversationState.fromJson(entity.getStateJson());
            state.setConversationId(conversationId);
            return state;
        }
        return null;
    }

    /**
     * 清除会话状态
     */
    public void clearState(String conversationId) {
        getBucket(conversationId).delete();
        // 同时清除 MySQL 中的状态
        try {
            QueryWrapper wrapper = QueryWrapper.create()
                    .eq(ConversationStateEntity::getConversationId, conversationId);
            conversationStateMapper.deleteByQuery(wrapper);
        } catch (Exception e) {
            log.warn("清除 MySQL 状态失败: conversationId={}", conversationId, e);
        }
        log.debug("清除对话状态: conversationId={}", conversationId);
    }

    /**
     * 合并槽位到现有状态，只覆盖非 null 字段。
     * 当上游槽位变更时，自动级联清空下游依赖槽位。
     *
     * <pre>
     *   city / film / cinema / showDate / hallType 变了 → schedule + seats + order 全清
     *   scheduleId 变了                                   → seats + order 全清
     * </pre>
     */
    public ConversationState mergeState(String conversationId, ConversationState newSlots) {
        ConversationState state = getState(conversationId);

        if (newSlots == null) {
            return state;
        }

        // ── 检测上游变更标记 ──
        boolean searchPhaseChanged = false;

        if (newSlots.getCurrentCity() != null && !newSlots.getCurrentCity().equals(state.getCurrentCity())) {
            state.setCurrentCity(newSlots.getCurrentCity());
            searchPhaseChanged = true;
        }
        if (newSlots.getFilmId() != null && !newSlots.getFilmId().equals(state.getFilmId())) {
            state.setFilmId(newSlots.getFilmId());
            searchPhaseChanged = true;
        }
        if (newSlots.getFilmName() != null && !newSlots.getFilmName().equals(state.getFilmName())) {
            state.setFilmName(newSlots.getFilmName());
            searchPhaseChanged = true;
        }
        if (newSlots.getCinemaId() != null && !newSlots.getCinemaId().equals(state.getCinemaId())) {
            state.setCinemaId(newSlots.getCinemaId());
            searchPhaseChanged = true;
        }
        if (newSlots.getCinemaName() != null && !newSlots.getCinemaName().equals(state.getCinemaName())) {
            state.setCinemaName(newSlots.getCinemaName());
            searchPhaseChanged = true;
            // ★ 用户明确换了影院名 → 旧的 cinemaId 可能对应旧影院，清空让节点按新名称重新解析
            //   （LLM 通常只提取名称不给 ID，若不清理会带着旧 cinemaId 搜错影院）
            if (newSlots.getCinemaId() == null) {
                state.setCinemaId(null);
            }
        }
        if (newSlots.getShowDate() != null && !newSlots.getShowDate().equals(state.getShowDate())) {
            state.setShowDate(newSlots.getShowDate());
            searchPhaseChanged = true;
        }
        if (newSlots.getHallType() != null && !newSlots.getHallType().equals(state.getHallType())) {
            state.setHallType(newSlots.getHallType());
            searchPhaseChanged = true;
        }

        // 上游变了 → 下游作废（清场次/座位/订单；保留已选日期与时间——用户换厅/换片不改变观影日期）
        if (searchPhaseChanged) {
            state.setScheduleId(null);
            state.setHallName(null);
            // 换影院/换片/换日期时，本轮未指定厅型则清除残留 hallType（旧厅型会把新影院的场次全部过滤掉）
            if (newSlots.getHallType() == null) {
                state.setHallType(null);
            }
            state.setSeatIds(null);
            state.setSeatLabels(null);
            state.setOrderId(null);
            log.info("用户换了电影，清理之前的逻辑");
        }

        // 清理旧场次后恢复用户本轮明确指定的日期，供场次查询使用。
        if (newSlots.getShowDate() != null) state.setShowDate(newSlots.getShowDate());

        // ── 场次级变更 ──
        if (newSlots.getScheduleId() != null && !newSlots.getScheduleId().equals(state.getScheduleId())) {
            state.setScheduleId(newSlots.getScheduleId());
            state.setSeatIds(null);
            state.setSeatLabels(null);
            state.setOrderId(null);
        }

        // ── 不触发级联的独立字段 ──
        if (newSlots.getFilmType() != null) state.setFilmType(newSlots.getFilmType());
        if (newSlots.getStartTime() != null) state.setStartTime(newSlots.getStartTime());
        if (newSlots.getHallName() != null) state.setHallName(newSlots.getHallName());
        if (newSlots.getTicketCount() != null) state.setTicketCount(newSlots.getTicketCount());
        if (newSlots.getOrderId() != null) state.setOrderId(newSlots.getOrderId());
        if (newSlots.getPreferredSeatZone() != null) state.setPreferredSeatZone(newSlots.getPreferredSeatZone());
        if (newSlots.getUserId() != null) state.setUserId(newSlots.getUserId());
        if (newSlots.getSeatIds() != null && !newSlots.getSeatIds().isEmpty()) {
            state.setSeatIds(newSlots.getSeatIds());
        }
        if (newSlots.getSeatLabels() != null && !newSlots.getSeatLabels().isEmpty()) {
            state.setSeatLabels(newSlots.getSeatLabels());
        }

        saveState(conversationId, state);
        return state;
    }

    /**
     * 刷新状态 TTL（延长 30 分钟）
     */
    public void refreshTtl(String conversationId) {
        RBucket<String> bucket = getBucket(conversationId);
        bucket.expire(STATE_TTL);
    }

    private RBucket<String> getBucket(String conversationId) {
        return redissonClient.getBucket(KEY_PREFIX + conversationId);
    }
}
