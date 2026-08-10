package com.limou.agent.service.impl;

import com.limou.agent.mapper.ChatSessionMapper;
import com.limou.agent.model.entity.ChatSession;
import com.limou.agent.service.ChatSessionService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 *  服务层实现。
 *
 * @author 李振南
 */
@Slf4j
@Service
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatSessionService {

    @Override
    public ChatSession getCurrent(Long userId) {
        QueryWrapper wrapper = QueryWrapper.create()
                .eq(ChatSession::getUserId, userId)
                .orderBy(ChatSession::getEditTime, false)
                .limit(1);
        ChatSession session = getOne(wrapper);
        if (session != null) {
            log.info("复用已有会话: sessionId={}, userId={}", session.getId(), userId);
            return session;
        }
        // 新用户无会话 → 自动创建
        return createNew(userId);
    }

    @Override
    public ChatSession createNew(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        ChatSession session = ChatSession.builder()
                .sessionName("新对话")
                .userId(userId)
                .createTime(now)
                .editTime(now)
                .build();
        save(session);
        log.info("新建会话: sessionId={}, userId={}", session.getId(), userId);
        return session;
    }

    @Override
    public java.util.List<ChatSession> listByUser(Long userId) {
        QueryWrapper wrapper = QueryWrapper.create()
                .eq(ChatSession::getUserId, userId)
                .orderBy(ChatSession::getEditTime, false);
        return list(wrapper);
    }

    @Override
    public boolean rename(Long sessionId, String newName) {
        ChatSession session = getById(sessionId);
        if (session == null) return false;
        session.setSessionName(newName);
        session.setEditTime(LocalDateTime.now());
        return updateById(session);
    }

}
