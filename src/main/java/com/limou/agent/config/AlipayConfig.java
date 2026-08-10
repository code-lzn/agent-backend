package com.limou.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 支付宝沙箱配置。
 *
 * @author 李振南
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "alipay")
public class AlipayConfig {
    private String appId;
    private String appPrivateKey;
    private String alipayPublicKey;
    private String notifyUrl;
    private String returnUrl;
    private String signType = "RSA2";
    private String frontendUrl;
    private String charset = "utf-8";
    private String format = "json";
    private String gatewayUrl = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
}
