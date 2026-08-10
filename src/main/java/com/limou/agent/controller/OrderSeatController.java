package com.limou.agent.controller;

import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.constant.UserConstant;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import com.limou.agent.model.entity.OrderSeat;
import com.limou.agent.service.OrderSeatService;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 *  控制层。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/orderSeat")
public class OrderSeatController {

    @Autowired
    private OrderSeatService orderSeatService;

    /**
     * 保存。
     *
     * @param orderSeat
     * @return {@code true} 保存成功，{@code false} 保存失败
     */
    @PostMapping("save")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> save(@RequestBody OrderSeat orderSeat) {
        return ResultUtils.success(orderSeatService.save(orderSeat));
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
        return ResultUtils.success(orderSeatService.removeById(id));
    }

    /**
     * 根据主键更新。
     *
     * @param orderSeat
     * @return {@code true} 更新成功，{@code false} 更新失败
     */
    @PutMapping("update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> update(@RequestBody OrderSeat orderSeat) {
        return ResultUtils.success(orderSeatService.updateById(orderSeat));
    }

    /**
     * 查询所有（B 端管理）。
     *
     * @return 所有数据
     */
    @GetMapping("list")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public List<OrderSeat> list() {
        return orderSeatService.list();
    }

    /**
     * 根据主键获取（B 端管理）。
     *
     * @param id 主键
     * @return 详情
     */
    @GetMapping("getInfo/{id}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public OrderSeat getInfo(@PathVariable Long id) {
        return orderSeatService.getById(id);
    }

    /**
     * 分页查询（B 端管理）。
     *
     * @param page 分页对象
     * @return 分页对象
     */
    @GetMapping("page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Page<OrderSeat> page(Page<OrderSeat> page) {
        return orderSeatService.page(page);
    }

}
