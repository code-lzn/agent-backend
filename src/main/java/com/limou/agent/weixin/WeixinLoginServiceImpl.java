package com.limou.agent.weixin;

import com.github.benmanes.caffeine.cache.Cache;
import com.limou.agent.weixin.model.WeixinQrCodeReq;
import com.limou.agent.weixin.model.WeixinQrCodeRes;
import com.limou.agent.weixin.model.WeixinTemplateMessageVO;
import com.limou.agent.weixin.model.WeixinTokenRes;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import retrofit2.Call;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 微信登录服务实现
 */
@Slf4j
@Service
public class WeixinLoginServiceImpl implements IWeixinLoginService {

    @Resource
    private WeixinProperties weixinProperties;

    @Resource
    private Cache<String, String> openidTokenCache;

    @Resource
    private IWeixinApiService weixinApiService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String TICKET_REDIS_PREFIX = "weixin:ticket:";
    private static final long TICKET_REDIS_TTL_MINUTES = 5;
    private static final String ACCESS_TOKEN_REDIS_KEY = "weixin:access_token";
    /** access_token 有效期 7200s，Redis 存 7100s 提前刷新 */
    private static final long ACCESS_TOKEN_REDIS_TTL = 7100;

    /**
     * 获取微信公众号 access_token（Redis 共享，多实例安全）
     * <p>
     * 所有实例共享同一个 token，避免各实例独立获取 token 导致互踢。
     */
    private String getAccessToken() throws IOException {
        // 1. 先从 Redis 读共享的 token
        String token = stringRedisTemplate.opsForValue().get(ACCESS_TOKEN_REDIS_KEY);
        if (token != null && !token.isEmpty()) {
            return token;
        }
        // 2. Redis 没有或已过期，请求微信获取新 token
        Call<WeixinTokenRes> call = weixinApiService.getToken("client_credential",
                weixinProperties.getAppId(), weixinProperties.getAppSecret());
        WeixinTokenRes res = call.execute().body();
        if (res == null) {
            throw new RuntimeException("获取微信 access_token 失败：响应为空");
        }
        if (res.getErrcode() != null && !"0".equals(res.getErrcode())) {
            throw new RuntimeException("获取微信 access_token 失败：[errcode=" + res.getErrcode() + "] " + res.getErrmsg());
        }
        if (res.getAccess_token() == null) {
            throw new RuntimeException("获取微信 access_token 失败：access_token 为空");
        }
        token = res.getAccess_token();
        // 3. 写入 Redis 共享（TTL 略小于微信有效期，确保提前刷新）
        stringRedisTemplate.opsForValue().set(ACCESS_TOKEN_REDIS_KEY, token,
                ACCESS_TOKEN_REDIS_TTL, TimeUnit.SECONDS);
        log.info("微信 access_token 已刷新并写入 Redis 共享");
        return token;
    }

    @Override
    public String createQrCodeTicket() throws Exception {
        String accessToken = getAccessToken();

        // 生成 ticket（使用唯一 scene_id，防止多个用户同时扫码时 ticket 冲突）
        String sceneStr = "login_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 100000);
        WeixinQrCodeReq weixinQrCodeReq = WeixinQrCodeReq.builder()
                .expire_seconds(300)
                .action_name(WeixinQrCodeReq.ActionNameTypeVO.QR_STR_SCENE.getCode())
                .action_info(WeixinQrCodeReq.ActionInfo.builder()
                        .scene(WeixinQrCodeReq.ActionInfo.Scene.builder()
                                .scene_str(sceneStr)
                                .build())
                        .build())
                .build();

        Call<WeixinQrCodeRes> call = weixinApiService.createQrCode(accessToken, weixinQrCodeReq);
        WeixinQrCodeRes res = call.execute().body();
        if (res == null) {
            throw new RuntimeException("生成微信二维码失败：响应为空");
        }
        if (res.getErrcode() != null && res.getErrcode() != 0) {
            String err = "生成微信二维码失败：[errcode=" + res.getErrcode() + "] " + res.getErrmsg();
            log.error(err);
            throw new RuntimeException(err);
        }
        log.info("微信二维码生成成功: ticket={}, sceneStr={}", res.getTicket(), sceneStr);
        return res.getTicket();
    }

    @Override
    public String checkLogin(String ticket) {
        // 优先查本地 Caffeine 缓存
        String openid = openidTokenCache.getIfPresent(ticket);
        if (openid != null) {
            return openid;
        }
        // 回退查 Redis
        try {
            openid = stringRedisTemplate.opsForValue().get(TICKET_REDIS_PREFIX + ticket);
        } catch (Exception e) {
            log.warn("Redis 查询 ticket→openid 失败: {}", e.getMessage());
        }
        if (openid != null) {
            openidTokenCache.put(ticket, openid);
        }
        return openid;
    }

    @Override
    public void saveLoginState(String ticket, String openid) throws IOException {
        openidTokenCache.put(ticket, openid);
        try {
            stringRedisTemplate.opsForValue().set(
                TICKET_REDIS_PREFIX + ticket, openid,
                TICKET_REDIS_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis 写入 ticket→openid 失败（不影响本地缓存）: {}", e.getMessage());
        }

        // 发送模板消息（失败不影响登录）
        try {
            String accessToken = getAccessToken();
            Map<String, Map<String, String>> data = new HashMap<>();
            WeixinTemplateMessageVO.put(data, WeixinTemplateMessageVO.TemplateKey.USER, openid);
            WeixinTemplateMessageVO templateMessageDTO = new WeixinTemplateMessageVO(openid,
                    weixinProperties.getTemplateId());
            templateMessageDTO.setUrl("https://gaga.plus");
            templateMessageDTO.setData(data);
            Call<Void> call = weixinApiService.sendMessage(accessToken, templateMessageDTO);
            call.execute();
        } catch (Exception e) {
            log.warn("发送模板消息失败（不影响登录）: {}", e.getMessage());
        }
    }

}
