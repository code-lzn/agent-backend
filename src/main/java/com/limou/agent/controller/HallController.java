package com.limou.agent.controller;

import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.constant.UserConstant;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.ResultUtils;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import com.limou.agent.model.entity.Hall;
import com.limou.agent.service.HallService;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 *  控制层。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/hall")
public class HallController {

    @Autowired
    private HallService hallService;

    /**
     * 保存。
     *
     * @param hall
     * @return {@code true} 保存成功，{@code false} 保存失败
     */
    @PostMapping("save")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> save(@RequestBody Hall hall) {
        return ResultUtils.success(hallService.save(hall));
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
        return ResultUtils.success(hallService.removeById(id));
    }

    /**
     * 根据主键更新。
     *
     * @param hall
     * @return {@code true} 更新成功，{@code false} 更新失败
     */
    @PutMapping("update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> update(@RequestBody Hall hall) {
        return ResultUtils.success(hallService.updateById(hall));
    }

    /**
     * 查询所有。
     *
     * @return 所有数据
     */
    @GetMapping("list")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<Hall>> list() {
        return ResultUtils.success(hallService.list());
    }

    /**
     * 根据主键获取。
     *
     * @param id 主键
     * @return 详情
     */
    @GetMapping("getInfo/{id}")
    public BaseResponse<Hall> getInfo(@PathVariable Long id) {
        return ResultUtils.success(hallService.getById(id));
    }

    /**
     * 分页查询。
     *
     * @param page 分页对象
     * @return 分页对象
     */
    @GetMapping("page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Hall>> page(Page<Hall> page) {
        return ResultUtils.success(hallService.page(page));
    }

    /**
     * 根据影院ID获取影厅列表。
     */
    @GetMapping("/listByCinema/{cinemaId}")
    public BaseResponse<List<Hall>> listByCinema(@PathVariable Long cinemaId) {
        QueryWrapper qw = QueryWrapper.create().eq("cinemaId", cinemaId);
        List<Hall> list = hallService.list(qw);
        return ResultUtils.success(list);
    }

}
