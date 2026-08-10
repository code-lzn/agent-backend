package com.limou.agent.controller;

import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.constant.UserConstant;
import com.limou.agent.exception.BusinessException;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.exception.ThrowUtils;
import com.limou.agent.model.entity.ChatSession;
import com.limou.agent.model.entity.User;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 *  控制层。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/chatSession")
public class ChatSessionController {

    @Resource
    private ChatSessionService chatSessionService;

    @Resource
    private UserService userService;

    /**
     * 保存。
     *
     * @param chatSession
     * @return {@code true} 保存成功，{@code false} 保存失败
     */
    @PostMapping("save")
    public BaseResponse<Boolean> save(@RequestBody ChatSession chatSession, HttpServletRequest request) {
        // ★ 归属：保存的 userId 强制取当前登录用户，不信任前端传入
        Long userId = userService.getLoginUser(request).getId();
        chatSession.setUserId(userId);
        return ResultUtils.success(chatSessionService.save(chatSession));
    }

    /**
     * 根据主键删除。
     *
     * @param id 主键
     * @return {@code true} 删除成功，{@code false} 删除失败
     */
    @DeleteMapping("remove/{id}")
    public BaseResponse<Boolean> remove(@PathVariable Long id, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ChatSession chatSession = chatSessionService.getById(id);
        ThrowUtils.throwIf(chatSession == null, ErrorCode.NOT_FOUND_ERROR);
        // ★ 归属：非管理员只能删自己的会话
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())
                && !loginUser.getId().equals(chatSession.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能删除自己的会话");
        }
        return ResultUtils.success(chatSessionService.removeById(id));
    }

    /**
     * 根据主键更新。
     *
     * @param chatSession
     * @return {@code true} 更新成功，{@code false} 更新失败
     */
    @PutMapping("update")
    public BaseResponse<Boolean> update(@RequestBody ChatSession chatSession, HttpServletRequest request) {
        Long id = chatSession.getId();
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        ChatSession dbSession = chatSessionService.getById(id);
        ThrowUtils.throwIf(dbSession == null, ErrorCode.NOT_FOUND_ERROR);
        // ★ 归属：非管理员只能改自己的会话
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())
                && !loginUser.getId().equals(dbSession.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能修改自己的会话");
        }
        // ★ 归属：不允许通过更新接口把会话转给别人
        chatSession.setUserId(dbSession.getUserId());
        return ResultUtils.success(chatSessionService.updateById(chatSession));
    }

    /**
     * 查询所有（非管理员只返回自己的；保留裸数组返回类型以兼容前端 API 定义）。
     *
     * @return 所有数据
     */
    @GetMapping("list")
    public List<ChatSession> list(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        QueryWrapper queryWrapper = QueryWrapper.create();
        // ★ 归属：非管理员强制按自己的 userId 过滤
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            queryWrapper.eq("userId", loginUser.getId());
        }
        return chatSessionService.list(queryWrapper);
    }

    /**
     * 根据主键获取（非管理员只能查自己的会话；保留裸对象返回类型以兼容前端 API 定义）。
     *
     * @param id 主键
     * @return 详情
     */
    @GetMapping("getInfo/{id}")
    public ChatSession getInfo(@PathVariable Long id, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ChatSession chatSession = chatSessionService.getById(id);
        ThrowUtils.throwIf(chatSession == null, ErrorCode.NOT_FOUND_ERROR);
        // ★ 归属：非管理员只能查自己的会话
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())
                && !loginUser.getId().equals(chatSession.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能查看自己的会话");
        }
        return chatSession;
    }

    /**
     * 分页查询（B 端管理）。
     *
     * @param page 分页对象
     * @return 分页对象
     */
    @GetMapping("page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Page<ChatSession> page(Page<ChatSession> page) {
        return chatSessionService.page(page);
    }

    /**
     * 获取当前用户的活跃会话（最新一条，无则返回 null）
     */
    @GetMapping("current")
    public BaseResponse<ChatSession> getCurrentSession(@RequestParam Long userId, HttpServletRequest request) {
        // ★ 归属：非管理员只能查自己的会话
        User loginUser = userService.getLoginUser(request);
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            userId = loginUser.getId();
        }
        ChatSession session = chatSessionService.getCurrent(userId);
        return ResultUtils.success(session);
    }

    /**
     * 强制创建新会话（点击"新对话"按钮时调用）
     */
    @PostMapping("create")
    public BaseResponse<ChatSession> create(@RequestParam Long userId, HttpServletRequest request) {
        // ★ 归属：非管理员只能为自己创建会话
        User loginUser = userService.getLoginUser(request);
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            userId = loginUser.getId();
        }
        return ResultUtils.success(chatSessionService.createNew(userId));
    }

    /**
     * 查询用户的所有会话列表（历史记录）
     */
    @GetMapping("listByUser")
    public BaseResponse<List<ChatSession>> listByUser(@RequestParam Long userId, HttpServletRequest request) {
        // ★ 归属：非管理员只能查自己的会话
        User loginUser = userService.getLoginUser(request);
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            userId = loginUser.getId();
        }
        return ResultUtils.success(chatSessionService.listByUser(userId));
    }

    /**
     * 重命名会话
     */
    @PutMapping("rename")
    public BaseResponse<Boolean> rename(@RequestParam Long id, @RequestParam String name, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ChatSession chatSession = chatSessionService.getById(id);
        ThrowUtils.throwIf(chatSession == null, ErrorCode.NOT_FOUND_ERROR);
        // ★ 归属：非管理员只能重命名自己的会话
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())
                && !loginUser.getId().equals(chatSession.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能重命名自己的会话");
        }
        return ResultUtils.success(chatSessionService.rename(id, name));
    }

}
