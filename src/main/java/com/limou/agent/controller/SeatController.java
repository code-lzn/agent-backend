package com.limou.agent.controller;

import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.constant.UserConstant;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.exception.ThrowUtils;
import com.limou.agent.model.vo.SeatMapVO;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.limou.agent.model.entity.Seat;
import com.limou.agent.service.SeatService;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 座位 控制层。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/seat")
public class SeatController {

    @Autowired
    private SeatService seatService;

    // ========== 前台接口 ==========

    /**
     * 获取场次座位图。
     */
    @GetMapping("/seatmap/{scheduleId}")
    public BaseResponse<SeatMapVO> getSeatMap(@PathVariable Long scheduleId) {
        SeatMapVO seatMap = seatService.getSeatMap(scheduleId);
        return ResultUtils.success(seatMap);
    }

    // ========== 后台管理接口 ==========

    @PostMapping("save")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> save(@RequestBody Seat seat) {
        ThrowUtils.throwIf(seat == null, ErrorCode.PARAMS_ERROR);
        boolean result = seatService.save(seat);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(seat.getId());
    }

    @DeleteMapping("remove/{id}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> remove(@PathVariable Long id) {
        return ResultUtils.success(seatService.removeById(id));
    }

    @PutMapping("update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> update(@RequestBody Seat seat) {
        boolean result = seatService.updateById(seat);
        return ResultUtils.success(result);
    }

    @GetMapping("listAll")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<Seat>> listAll() {
        return ResultUtils.success(seatService.list());
    }

    @GetMapping("getInfo/{id}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Seat> getInfo(@PathVariable Long id) {
        return ResultUtils.success(seatService.getById(id));
    }

    @PostMapping("page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Seat>> page(@RequestBody Page<Seat> page) {
        return ResultUtils.success(seatService.page(page));
    }

}
