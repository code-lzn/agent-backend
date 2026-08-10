package com.limou.agent.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.DeleteRequest;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.constant.UserConstant;
import com.limou.agent.exception.BusinessException;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.exception.ThrowUtils;
import com.limou.agent.model.dto.user.*;
import com.limou.agent.model.vo.LoginUserVO;
import com.limou.agent.model.vo.UserVO;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.limou.agent.model.entity.User;
import com.limou.agent.service.UserService;

import java.util.List;

/**
 * 用户 控制层。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 用户 注册。
     *
     * @param userRegisterRequest 用户注册请求
     * @return 用户ID
     */

    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        //1.校验
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);
        Long register = userService.register(userRegisterRequest.getUserAccount(), userRegisterRequest.getUserPassword(), userRegisterRequest.getCheckPassword());
        return ResultUtils.success(register);
    }

    /**
     * 用户 登录。
     *
     * @param userRegisterRequest 用户登录请求
     * @return 登录用户信息
     */

    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserRegisterRequest userRegisterRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userService.userLogin(userRegisterRequest.getUserAccount(), userRegisterRequest.getUserPassword(), request));
    }
    /**
     * 获取当前登录用户。
     *
     * @param request 请求
     * @return 登录用户信息
     */


    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }

    /**
     * 当前用户修改自己的个人信息（昵称 / 头像 / 简介）。
     * 无需管理员权限，登录即可。
     */
    @PostMapping("/update/my")
    public BaseResponse<LoginUserVO> updateMyProfile(@RequestBody UserUpdateRequest updateRequest,
                                                      HttpServletRequest request) {
        ThrowUtils.throwIf(updateRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        // 只允许修改昵称、头像、简介
        if (updateRequest.getUserName() != null) loginUser.setUserName(updateRequest.getUserName());
        if (updateRequest.getUserAvatar() != null) loginUser.setUserAvatar(updateRequest.getUserAvatar());
        if (updateRequest.getUserProfile() != null) loginUser.setUserProfile(updateRequest.getUserProfile());
        userService.updateById(loginUser);
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }

    /**
     * 创建用户
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addUser(@RequestBody UserAddRequest userAddRequest) {
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        User user = new User();
        BeanUtil.copyProperties(userAddRequest, user);
        boolean result = userService.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(user.getId());
    }

    /**
     * 根据 id 获取用户（仅管理员）
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<User> getUserById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(user);
    }

    /**
     * 根据 id 获取包装类
     */
    @GetMapping("/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<UserVO> getUserVOById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(userService.getUserVO(user));
    }

    /**
     * 删除用户
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        boolean b = userService.removeById(deleteRequest.getId());
        return ResultUtils.success(b);
    }

    /**
     * 更新用户
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest) {
        if (userUpdateRequest == null || userUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = new User();
        BeanUtil.copyProperties(userUpdateRequest, user);
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 分页获取用户封装列表（仅管理员）
     *
     * @param userQueryRequest 查询请求参数
     */
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest) {
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = userQueryRequest.getPageNum();
        long pageSize = userQueryRequest.getPageSize();
        Page<User> userPage = userService.page(Page.of(pageNum, pageSize),
                userService.getQueryWrapper(userQueryRequest));
        // 数据脱敏
        Page<UserVO> userVOPage = new Page<>(pageNum, pageSize, userPage.getTotalRow());
        List<UserVO> userVOList = userService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVOList);
        return ResultUtils.success(userVOPage);
    }

    /**
     * 发送邮箱验证码
     */
    @PostMapping("/send-mail-code")
    public BaseResponse<String> sendMailCode(@RequestBody SendMailCodeRequest req) {
        ThrowUtils.throwIf(req == null, ErrorCode.PARAMS_ERROR);
        userService.sendMailCode(req.getEmail());
        return ResultUtils.success("验证码已发送");
    }
    /**
     * 邮箱验证码登录 / 自动注册
     */
    @PostMapping("/login-by-mail")
    public BaseResponse<LoginUserVO> mailLogin(@RequestBody MailLoginRequest req,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(req == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userService.mailLogin(req.getEmail(), req.getCode(), request));
    }
    /**
     * 通过邮箱验证码重置密码
     */
    @PostMapping("/reset-password")
    public BaseResponse<String> resetPassword(@RequestBody ResetPasswordRequest req) {
        ThrowUtils.throwIf(req == null, ErrorCode.PARAMS_ERROR);
        userService.resetPassword(req.getEmail(), req.getCode(),
                req.getNewPassword(), req.getCheckPassword());
        return ResultUtils.success("密码重置成功");
    }

    /**
     * 设置登录密码（当前密码为默认值时使用，无需旧密码）
     */
    @PostMapping("/set-password")
    public BaseResponse<String> setPassword(@RequestBody SetPasswordRequest req,
                                             HttpServletRequest request) {
        ThrowUtils.throwIf(req == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        userService.setPassword(loginUser.getId(), req.getNewPassword(), req.getCheckPassword());
        return ResultUtils.success("密码设置成功");
    }

    /**
     * 修改登录密码（需校验旧密码）
     */
    @PostMapping("/change-password")
    public BaseResponse<String> changePassword(@RequestBody ChangePasswordRequest req,
                                                HttpServletRequest request) {
        ThrowUtils.throwIf(req == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        userService.changePassword(loginUser.getId(),
                req.getOldPassword(), req.getNewPassword(), req.getCheckPassword());
        return ResultUtils.success("密码修改成功");
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        boolean logout = userService.userLogout(request);
        return ResultUtils.success(logout);
    }

    /**
     * 微信扫码登录 / 自动注册
     */
    @PostMapping("/login-by-weixin")
    public BaseResponse<LoginUserVO> weixinLogin(@RequestParam("openid") String openid,
                                                  HttpServletRequest request) {
        ThrowUtils.throwIf(StrUtil.isBlank(openid), ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userService.weixinLogin(openid, request));
    }

    /**
     * 冻结 / 解冻账号（仅管理员）。status：0 正常 / 1 冻结
     * PRD 3.3.5：冻结后 C 端无法登录、无法发起购票
     */
    @PostMapping("/admin/freeze")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> freezeUser(@RequestParam("id") Long id,
                                            @RequestParam("status") Integer status) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(status == null || (status != 0 && status != 1),
                ErrorCode.PARAMS_ERROR, "状态只能为 0（正常）或 1（冻结）");
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        // 管理员账号不允许被冻结
        ThrowUtils.throwIf(UserConstant.ADMIN_ROLE.equals(user.getUserRole()) && status == 1,
                ErrorCode.OPERATION_ERROR, "管理员账号不允许冻结");
        user.setUserStatus(status);
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 后台 - 重置用户密码。传 newPassword 则设为指定密码，不传则清空密码。仅管理员。PRD 3.3.5
     */
    @PostMapping("/admin/reset-password")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> adminResetPassword(@RequestParam("id") Long id,
                                                    @RequestParam(value = "newPassword", required = false) String newPassword) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        if (StrUtil.isNotBlank(newPassword)) {
            user.setUserPassword(userService.encryptPassword(newPassword));
        } else {
            user.setUserPassword(userService.encryptPassword("12345678"));
        }
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

}
