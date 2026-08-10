package com.limou.agent.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 座位状态枚举
 *
 * @author 李振南
 */
@Getter
public enum SeatStatusEnum {

    AVAILABLE("available", "可选"),
    LOCKED("locked", "已锁定"),
    SOLD("sold", "已售出");

    private final String value;
    private final String text;

    SeatStatusEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public static SeatStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (SeatStatusEnum anEnum : SeatStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
