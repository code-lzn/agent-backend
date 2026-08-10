package com.limou.agent.weixin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 获取微信登录二维码响应对象
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeixinQrCodeRes {
    private String ticket;
    private Long expire_seconds;
    private String url;
    private Integer errcode;
    private String errmsg;
}
