package com.limou.agent.controller;

import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.constant.UserConstant;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.exception.ThrowUtils;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import com.limou.agent.model.entity.SystemConfig;
import com.limou.agent.service.SystemConfigService;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 *  控制层。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/systemConfig")
public class SystemConfigController {

    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 保存。
     *
     * @param systemConfig 
     * @return {@code true} 保存成功，{@code false} 保存失败
     */
    @PostMapping("save")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> save(@RequestBody SystemConfig systemConfig) {
        return ResultUtils.success(systemConfigService.save(systemConfig));
    }

    /**
     * 根据主键删除。
     *
     * @param id 主键
     * @return {@code true} 删除成功，{@code false} 删除失败
     */
    @DeleteMapping("remove/{id}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> remove(@PathVariable Long id) {
        return ResultUtils.success(systemConfigService.removeById(id));
    }

    /**
     * 根据主键更新。
     *
     * @param systemConfig
     * @return {@code true} 更新成功，{@code false} 更新失败
     */
    @PutMapping("update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> update(@RequestBody SystemConfig systemConfig) {
        return ResultUtils.success(systemConfigService.updateById(systemConfig));
    }

    /**
     * 查询所有。
     *
     * @return 所有数据
     */
    @GetMapping("list")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<SystemConfig>> list() {
        return ResultUtils.success(systemConfigService.list());
    }

    /**
     * 根据主键获取。
     *
     * @param id 主键
     * @return 详情
     */
    @GetMapping("getInfo/{id}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<SystemConfig> getInfo(@PathVariable Long id) {
        return ResultUtils.success(systemConfigService.getById(id));
    }

    /**
     * 分页查询。
     *
     * @param page 分页对象
     * @return 分页对象
     */
    @GetMapping("page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<SystemConfig>> page(Page<SystemConfig> page) {
        return ResultUtils.success(systemConfigService.page(page));
    }

    // ========== 后台管理接口 ==========

    /**
     * 根据配置键获取。
     */
    @GetMapping("/getByKey/{configKey}")
    public BaseResponse<SystemConfig> getByKey(@PathVariable String configKey) {
        QueryWrapper qw = QueryWrapper.create().eq("configKey", configKey);
        SystemConfig config = systemConfigService.getOne(qw);
        return ResultUtils.success(config);
    }

    /**
     * 根据配置键更新。
     */
    @PutMapping("/updateByKey")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateByKey(@RequestBody SystemConfig systemConfig) {
        ThrowUtils.throwIf(systemConfig == null || systemConfig.getConfigKey() == null, ErrorCode.PARAMS_ERROR);
        QueryWrapper qw = QueryWrapper.create().eq("configKey", systemConfig.getConfigKey());
        SystemConfig old = systemConfigService.getOne(qw);
        if (old == null) {
            return ResultUtils.success(systemConfigService.save(systemConfig));
        }
        systemConfig.setId(old.getId());
        return ResultUtils.success(systemConfigService.updateById(systemConfig));
    }

}
