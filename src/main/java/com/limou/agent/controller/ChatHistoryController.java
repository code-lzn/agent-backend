package com.limou.agent.controller;

import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.DeleteRequest;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.constant.UserConstant;
import com.limou.agent.exception.BusinessException;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.exception.ThrowUtils;
import com.limou.agent.model.dto.chathistory.ChatHistoryQueryRequest;
import com.limou.agent.model.entity.ChatHistory;
import com.limou.agent.model.entity.ChatSession;
import com.limou.agent.model.entity.User;
import com.limou.agent.service.ChatHistoryService;
import com.limou.agent.service.ChatSessionService;
import com.limou.agent.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话历史 控制层。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/chatHistory")
public class ChatHistoryController {

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private ChatSessionService chatSessionService;

    @Resource
    private UserService userService;

    /**
     * 保存对话历史。
     */
    @PostMapping("save")
    public BaseResponse<Boolean> save(@RequestBody ChatHistory chatHistory, HttpServletRequest request) {
        // ★ 归属：保存的 userId 强制取当前登录用户，不信任前端传入
        Long userId = userService.getLoginUser(request).getId();
        chatHistory.setUserId(userId);
        return ResultUtils.success(chatHistoryService.save(chatHistory));
    }

    /**
     * 根据主键删除对话历史。
     */
    @DeleteMapping("remove/{id}")
    public BaseResponse<Boolean> remove(@PathVariable Long id, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ChatHistory chatHistory = chatHistoryService.getById(id);
        ThrowUtils.throwIf(chatHistory == null, ErrorCode.NOT_FOUND_ERROR);
        // ★ 归属：非管理员只能删自己的历史
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())
                && !loginUser.getId().equals(chatHistory.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能删除自己的对话记录");
        }
        return ResultUtils.success(chatHistoryService.removeById(id));
    }

    /**
     * 根据主键更新对话历史。
     */
    @PutMapping("update")
    public BaseResponse<Boolean> update(@RequestBody ChatHistory chatHistory, HttpServletRequest request) {
        Long id = chatHistory.getId();
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        ChatHistory dbHistory = chatHistoryService.getById(id);
        ThrowUtils.throwIf(dbHistory == null, ErrorCode.NOT_FOUND_ERROR);
        // ★ 归属：非管理员只能改自己的历史
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())
                && !loginUser.getId().equals(dbHistory.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能修改自己的对话记录");
        }
        // ★ 归属：不允许通过更新接口把记录转给别人
        chatHistory.setUserId(dbHistory.getUserId());
        return ResultUtils.success(chatHistoryService.updateById(chatHistory));
    }

    /**
     * 根据主键获取对话历史（非管理员只能查自己的）。
     */
    @GetMapping("getInfo/{id}")
    public BaseResponse<ChatHistory> getInfo(@PathVariable Long id, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ChatHistory chatHistory = chatHistoryService.getById(id);
        ThrowUtils.throwIf(chatHistory == null, ErrorCode.NOT_FOUND_ERROR);
        // ★ 归属：非管理员只能查自己的历史
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())
                && !loginUser.getId().equals(chatHistory.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能查看自己的对话记录");
        }
        return ResultUtils.success(chatHistory);
    }

    /**
     * 查询对话历史（非管理员只返回自己的）。
     */
    @GetMapping("list")
    public BaseResponse<List<ChatHistory>> list(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        QueryWrapper queryWrapper = QueryWrapper.create();
        // ★ 归属：非管理员强制按自己的 userId 过滤
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            queryWrapper.eq("userId", loginUser.getId());
        }
        return ResultUtils.success(chatHistoryService.list(queryWrapper));
    }

    /**
     * 分页查询对话历史（仅管理员）。
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ChatHistory>> page(@RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = chatHistoryQueryRequest.getPageNum();
        long pageSize = chatHistoryQueryRequest.getPageSize();
        QueryWrapper queryWrapper = chatHistoryService.getQueryWrapper(chatHistoryQueryRequest);
        Page<ChatHistory> result = chatHistoryService.page(Page.of(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(result);
    }

    /**
     * 根据主键删除对话历史（管理员）。
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> delete(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        return ResultUtils.success(chatHistoryService.removeById(deleteRequest.getId()));
    }

    /**
     * 根据会话ID查询对话历史（非管理员只能查自己会话下的历史）。
     */
    @GetMapping("/listBySession/{sessionId}")
    public BaseResponse<List<ChatHistory>> listBySession(@PathVariable Long sessionId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        // ★ 归属：非管理员只能查自己会话的历史
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            ChatSession session = chatSessionService.getById(sessionId);
            ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR);
            ThrowUtils.throwIf(!loginUser.getId().equals(session.getUserId()),
                    ErrorCode.NO_AUTH_ERROR, "只能查看自己的会话记录");
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("sessionId", sessionId)
                .orderBy("createTime", true)
                .orderBy("id", true);
        return ResultUtils.success(chatHistoryService.list(queryWrapper));
    }

}
