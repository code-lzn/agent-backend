package com.limou.agent.controller;

import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.constant.UserConstant;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.exception.ThrowUtils;
import com.limou.agent.model.entity.User;
import com.limou.agent.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import com.limou.agent.model.entity.UserPreference;
import com.limou.agent.service.UserPreferenceService;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 *  控制层。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/userPreference")
public class UserPreferenceController {

    @Autowired
    private UserPreferenceService userPreferenceService;

    @Resource
    private UserService userService;

    /**
     * 根据用户ID获取偏好（仅管理员）。PRD 3.3.5：查看用户偏好画像
     */
    @GetMapping("/byUser/{userId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<UserPreference> getByUser(@PathVariable Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userPreferenceService.getByUserId(userId));
    }

    /**
     * 获取当前用户的偏好。
     */
    @GetMapping("/my")
    public BaseResponse<UserPreference> getMyPreference(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        UserPreference pref = userPreferenceService.getByUserId(loginUser.getId());
        return ResultUtils.success(pref);
    }

    /**
     * 保存或更新当前用户的偏好。
     */
    @PostMapping("/my")
    public BaseResponse<Boolean> saveMyPreference(@RequestBody UserPreference preference,
                                                   HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        boolean result = userPreferenceService.saveOrUpdate(loginUser.getId(), preference);
        return ResultUtils.success(result);
    }

    /**
     * 保存。
     *
     * @param userPreference
     * @return {@code true} 保存成功，{@code false} 保存失败
     */
    @PostMapping("save")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> save(@RequestBody UserPreference userPreference) {
        return ResultUtils.success(userPreferenceService.save(userPreference));
    }

    /**
     * 重置（清空）指定用户的偏好画像（仅管理员）。PRD 3.3.5
     */
    @PostMapping("/reset/{userId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> resetByUser(@PathVariable Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR);
        boolean result = userPreferenceService.resetByUserId(userId);
        return ResultUtils.success(result);
    }

    /**
     * 根据主键删除（B 端管理）。
     *
     * @param id 主键
     * @return {@code true} 删除成功，{@code false} 删除失败
     */
    @DeleteMapping("remove/{id}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> remove(@PathVariable Long id) {
        return ResultUtils.success(userPreferenceService.removeById(id));
    }

    /**
     * 根据主键更新（B 端管理）。
     *
     * @param userPreference
     * @return {@code true} 更新成功，{@code false} 更新失败
     */
    @PutMapping("update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> update(@RequestBody UserPreference userPreference) {
        return ResultUtils.success(userPreferenceService.updateById(userPreference));
    }

    /**
     * 查询所有（B 端管理）。
     *
     * @return 所有数据
     */
    @GetMapping("list")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public List<UserPreference> list() {
        return userPreferenceService.list();
    }

    /**
     * 根据主键获取（B 端管理）。
     *
     * @param id 主键
     * @return 详情
     */
    @GetMapping("getInfo/{id}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public UserPreference getInfo(@PathVariable Long id) {
        return userPreferenceService.getById(id);
    }

    /**
     * 分页查询（B 端管理）。
     *
     * @param page 分页对象
     * @return 分页对象
     */
    @GetMapping("page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Page<UserPreference> page(Page<UserPreference> page) {
        return userPreferenceService.page(page);
    }

}
