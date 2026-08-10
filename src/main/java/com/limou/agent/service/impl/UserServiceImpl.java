package com.limou.agent.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import com.limou.agent.constant.UserConstant;
import com.limou.agent.exception.BusinessException;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.model.dto.user.UserQueryRequest;
import com.limou.agent.model.enums.UserRoleEnum;
import com.limou.agent.model.vo.LoginUserVO;
import com.limou.agent.model.vo.UserVO;
import com.limou.agent.utils.JwtUtils;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.limou.agent.model.entity.User;
import com.limou.agent.mapper.UserMapper;
import com.limou.agent.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * 用户 服务层实现。
 *
 * @author 李振南
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JavaMailSender mailSender;

    @Override
    public Long register(String userAccount, String userPassword, String checkPassword) {
        //1.校验
        if (StrUtil.hasBlank(userPassword, checkPassword, userAccount)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        //2.查询
        QueryWrapper queryWrapper = new QueryWrapper();
        QueryWrapper wrapper = queryWrapper.eq(User::getUserAccount, userAccount);
        long count = this.mapper.selectCountByQuery(wrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }
        //3.加密
        String encryptUserPassword = encryptPassword(userPassword);
        //4.保存
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptUserPassword);
        user.setUserName("无名");
        user.setUserRole(UserRoleEnum.USER.getValue());
        user.setUserStatus(0);
//        boolean save = this.mapper.(user) > 0;
        boolean save = save(user);
        if (!save) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败");
        }
        return user.getId();

    }

    @Override
    //加密
    public String encryptPassword(String userPassword) {
        String SALT = "limou";
        return DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        LoginUserVO vo = BeanUtil.copyProperties(user, LoginUserVO.class);
        // 密码为默认密码 12345678 时需要引导设置
        vo.setNeedSetPassword(
                encryptPassword("12345678").equals(user.getUserPassword())
        );
        return vo;


    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        //1.校验
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        //2.加密
        String encryptUserPassword = encryptPassword(userPassword);
        //3.查询
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(User::getUserAccount, userAccount).eq(User::getUserPassword, encryptUserPassword);
        User user = this.mapper.selectOneByQuery(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // PRD 3.3.5：冻结账号禁止登录
        checkUserFrozen(user);
        //4.记录用户的登录态
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        //5.返回
        return this.getLoginUserVO(user);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 1. 先尝试从 Session 获取（Cookie 认证，向后兼容）
        User userObj = (User) request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        // 2. Session 中没有时，尝试从 JWT Token 注入的 request attribute 获取
        if (userObj == null || userObj.getId() == null) {
            userObj = (User) request.getAttribute(UserConstant.USER_LOGIN_STATE);
        }
        if (userObj == null || userObj.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 预防用户修改用户信息之后没有修改缓存，因此要获得最新的数据信息
        User user = this.getById(userObj.getId());
        if (user == null || user.getIsDelete() == 1) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        //判断是不是有
        if (request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE) == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        request.getSession().removeAttribute(UserConstant.USER_LOGIN_STATE);
        return true;
    }


    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .eq("userRole", userRole)
                .like("userAccount", userAccount)
                .like("userName", userName)
                .like("userProfile", userProfile)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }



    // ==================== sendMailCode ====================
    @Async
    @Override
    public void sendMailCode(String email) {
        if (!Validator.isEmail(email)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        }
        // 60 秒内不允许重发
        String lastKey = UserConstant.MAIL_CODE_PREFIX + "last:" + email;
        String lastTime = stringRedisTemplate.opsForValue().get(lastKey);
        if (lastTime != null) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, "请60秒后再试");
        }
        String code = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 999999));
        String codeKey = UserConstant.MAIL_CODE_PREFIX + email;
        stringRedisTemplate.opsForValue().set(codeKey, code, 5, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(lastKey, "1", 59, TimeUnit.SECONDS);
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom("2215895433@qq.com");
        msg.setTo(email);
        msg.setSubject("妙语购票 - 邮箱验证码");
        msg.setText("您的验证码是：" + code + "，5分钟内有效。\n\n如非本人操作，请忽略此邮件。");
        try {
            mailSender.send(msg);
        } catch (Exception e) {
            // 验证码已存入 Redis，发送失败时需要删除避免泄漏
            stringRedisTemplate.delete(codeKey);
            stringRedisTemplate.delete(lastKey);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "邮件发送失败：" + e.getMessage());
        }
    }
    // ==================== mailLogin ====================
    @Override
    public LoginUserVO mailLogin(String email, String code, HttpServletRequest request) {
        if (StrUtil.isBlank(email) || StrUtil.isBlank(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (!Validator.isEmail(email)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        }
        String codeKey = UserConstant.MAIL_CODE_PREFIX + email;
        String savedCode = stringRedisTemplate.opsForValue().get(codeKey);
        if (savedCode == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码已过期，请重新获取");
        }
        if (!savedCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }
        stringRedisTemplate.delete(codeKey);
        // ★ 按 userAccount 查（userAccount 就是邮箱）
        User user = this.mapper.selectOneByQuery(
                new QueryWrapper().eq(User::getUserAccount, email));
        if (user == null) {
            // 自动注册
            user = new User();
            user.setUserAccount(email);                          // ★ userAccount = 邮箱
            user.setUserName(email.split("@")[0]);               // 默认昵称：@前面部分
            user.setUserPassword(encryptPassword("12345678"));
            user.setUserRole(UserRoleEnum.USER.getValue());
            save(user);
        }
        // PRD 3.3.5：冻结账号禁止登录
        checkUserFrozen(user);
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        return this.getLoginUserVO(user);
    }
    // ==================== resetPassword ====================
    @Override
    public void resetPassword(String email, String code, String newPassword, String checkPassword) {
        if (StrUtil.hasBlank(email, code, newPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (!Validator.isEmail(email)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        }
        if (newPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "新密码至少8位");
        }
        if (!newPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次密码不一致");
        }
        String codeKey = UserConstant.MAIL_CODE_PREFIX + email;
        String savedCode = stringRedisTemplate.opsForValue().get(codeKey);
        if (savedCode == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码已过期");
        }
        if (!savedCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }
        stringRedisTemplate.delete(codeKey);
        // ★ 按 userAccount 查
        User user = this.mapper.selectOneByQuery(
                new QueryWrapper().eq(User::getUserAccount, email));
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该邮箱未注册，请先用邮箱验证码登录");
        }
        user.setUserPassword(encryptPassword(newPassword));
        updateById(user);
    }

    // ==================== setPassword ====================
    @Override
    public void setPassword(Long userId, String newPassword, String checkPassword) {
        if (StrUtil.hasBlank(newPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (newPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码至少8位");
        }
        if (!newPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次密码不一致");
        }
        User user = this.getById(userId);
        if (user == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        user.setUserPassword(encryptPassword(newPassword));
        updateById(user);
    }

    // ==================== changePassword ====================
    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword, String checkPassword) {
        if (StrUtil.hasBlank(oldPassword, newPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (newPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码至少8位");
        }
        if (!newPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次密码不一致");
        }
        User user = this.getById(userId);
        if (user == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        if (!encryptPassword(oldPassword).equals(user.getUserPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旧密码不正确");
        }
        user.setUserPassword(encryptPassword(newPassword));
        updateById(user);
    }

    // ==================== weixinLogin ====================
    @Override
    public LoginUserVO weixinLogin(String openid, HttpServletRequest request) {
        if (StrUtil.isBlank(openid)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "openid 为空");
        }
        String weixinAccount = "wx_" + openid;
        // 按 userAccount 查
        User user = this.mapper.selectOneByQuery(
                new QueryWrapper().eq(User::getUserAccount, weixinAccount));
        if (user == null) {
            // 自动注册
            user = new User();
            user.setUserAccount(weixinAccount);
            user.setUserName("微信用户" + openid.substring(Math.max(0, openid.length() - 6)));
            user.setUserPassword(encryptPassword("12345678"));
            user.setUserRole(UserRoleEnum.USER.getValue());
            save(user);
        }
        // PRD 3.3.5：冻结账号禁止登录
        checkUserFrozen(user);
        // 保留 Session 认证（向后兼容 Cookie 方案）
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        // ★ 生成 JWT Token，前端存入 localStorage 后通过 Authorization header 携带
        LoginUserVO vo = this.getLoginUserVO(user);
        vo.setToken(JwtUtils.createToken(user.getId(), user.getUserRole()));
        return vo;
    }

    /**
     * 校验账号是否被冻结（userStatus = 1 表示冻结）
     */
    private void checkUserFrozen(User user) {
        if (user != null && Integer.valueOf(1).equals(user.getUserStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "账号已被冻结，无法登录，请联系管理员");
        }
    }

}
