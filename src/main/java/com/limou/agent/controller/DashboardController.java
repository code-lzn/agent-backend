package com.limou.agent.controller;

import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.constant.UserConstant;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.model.entity.Order;
import com.limou.agent.model.enums.OrderStatusEnum;
import com.limou.agent.service.*;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 运营数据看板。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private FilmService filmService;

    @Autowired
    private CinemaService cinemaService;

    @Autowired
    private UserService userService;

    @Autowired
    private ScheduleService scheduleService;

    @GetMapping("/stats")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<DashboardVO> getStats() {
        DashboardVO vo = new DashboardVO();

        // 今日订单数
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = LocalDate.now().atTime(LocalTime.MAX);
        QueryWrapper todayOrdersQw = QueryWrapper.create()
                .ge("createTime", todayStart)
                .le("createTime", todayEnd);
        vo.setTodayOrders(orderService.count(todayOrdersQw));

        // 今日收入
        QueryWrapper paidQw = QueryWrapper.create()
                .eq("status", OrderStatusEnum.PAID.getValue())
                .ge("createTime", todayStart)
                .le("createTime", todayEnd);
        vo.setTodayRevenue(orderService.list(paidQw).stream()
                .map(Order::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        // 总影片数
        QueryWrapper filmQw = QueryWrapper.create().eq("status", "published");
        vo.setTotalFilms(filmService.count(filmQw));

        // 总影院数
        vo.setTotalCinemas(cinemaService.count());

        // 总用户数
        vo.setTotalUsers(userService.count());

        // 今日场次数
        QueryWrapper scheduleQw = QueryWrapper.create()
                .eq("showDate", LocalDate.now());
        vo.setTodaySchedules(scheduleService.count(scheduleQw));

        return ResultUtils.success(vo);
    }

    @Data
    public static class DashboardVO {
        private long todayOrders;
        private BigDecimal todayRevenue = BigDecimal.ZERO;
        private long totalFilms;
        private long totalCinemas;
        private long totalUsers;
        private long todaySchedules;
    }
}
