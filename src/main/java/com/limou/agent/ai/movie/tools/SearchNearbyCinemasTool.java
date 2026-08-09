package com.limou.agent.ai.movie.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limou.agent.ai.movie.ConversationContext;
import com.limou.agent.ai.movie.MovieStateManager;
import com.limou.agent.mapper.CinemaMapper;
import com.limou.agent.mapper.ScheduleMapper;
import com.limou.agent.model.dto.movie.ConversationState;
import com.limou.agent.model.entity.Cinema;
import com.limou.agent.model.entity.Schedule;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.limou.agent.utils.GeoUtils;

import java.util.Comparator;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 附近影院搜索工具
 * 通过高德地图 API 进行地理编码 + POI 周边搜索，与系统影院数据库交叉匹配
 *
 * @author 李振南
 */
@Slf4j
@Component
public class SearchNearbyCinemasTool extends BaseTool {

    @Resource
    private CinemaMapper cinemaMapper;

    @Resource
    private ScheduleMapper scheduleMapper;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private MovieStateManager stateManager;

    @Value("${amap.api-key}")
    private String amapApiKey;

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String AROUND_SEARCH_URL = "https://restapi.amap.com/v3/place/around";

    private final RestTemplate restTemplate = new RestTemplate();

    @Tool(description = """
            搜索附近影院。用户说"附近"、"周围"、"离我近的影院"、"附近的电影院"时必须使用此工具。
            优先使用 lat/lng 精确定位，其次使用 location 文本进行地理编码。
            返回附近影院列表（含名称、地址、距离、经纬度、是否在系统中有排片）""")
    public String searchNearbyCinemas(
            @ToolParam(description = "位置描述，如'洛阳'、'洛阳市洛龙区'。用户说'附近'但未指定具体位置时，从对话状态中取当前城市。仅 lat/lng 都为空时才必需") String location,
            @ToolParam(description = "搜索半径（米），默认5000（5公里）。有精准坐标时可用小半径如2000", required = false) Integer radius,
            @ToolParam(description = "影片ID，传入则只返回有该片排片的影院（可选）", required = false) Long filmId,
            @ToolParam(description = "用户当前纬度（WGS84），从对话状态获取。传入后跳过地理编码，直接周边搜索（可选）", required = false) Double lat,
            @ToolParam(description = "用户当前经度（WGS84），从对话状态获取。传入后跳过地理编码，直接周边搜索（可选）", required = false) Double lng
    ) {
        try {
            int r = (radius != null && radius > 0) ? radius : 5000;

            // ========== Step 0: 优先使用传入坐标，否则地理编码 ==========
            double[] coords;
            if (lat != null && lng != null && lat != 0 && lng != 0) {
                coords = new double[]{lng, lat};
                log.info("searchNearbyCinemas 使用传入坐标: ({}, {}), radius={}", lat, lng, r);
            } else {
                coords = geocode(location);
                if (coords == null) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("cinemas", List.of());
                    err.put("total", 0);
                    err.put("error", "无法识别位置「" + location + "」，请尝试更具体的地址或城市名");
                    return objectMapper.writeValueAsString(err);
                }
            }
            double resultLng = coords[0];
            double resultLat = coords[1];

            // ========== Step 2: POI 周边搜索 — 搜索附近电影院 ==========
            List<AmapPoi> amapPois = searchAroundCinemas(resultLng, resultLat, r);

            // ========== Step 3: 与系统影院数据库匹配 ==========
            // ★ 即使 Amap 返回空，仍执行 matchWithDatabase：DB 中有坐标的影院可补充
            List<Map<String, Object>> matchedList = matchWithDatabase(amapPois, filmId, resultLng, resultLat, r);

            // ========== Step 4: 写回 ConversationState ==========
            writeBackToState(matchedList);

            // ========== Step 5: 构建返回结果 ==========
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("cinemas", matchedList);
            result.put("total", matchedList.size());
            result.put("center", location);
            result.put("centerLng", resultLng);
            result.put("centerLat", resultLat);
            result.put("radius", r);
            if (matchedList.isEmpty()) {
                result.put("message", "在「" + location + "」附近" + (r / 1000)
                        + "公里内未找到影院，可以试试扩大搜索范围或换个地点");
            }

            log.info("searchNearbyCinemas: location={}, radius={}, amapPois={}, matched={}",
                    location, r, amapPois.size(), matchedList.size());
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("searchNearbyCinemas 失败", e);
            return "{\"cinemas\":[],\"total\":0,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ==================== 地理编码 ====================

    private double[] geocode(String address) {
        try {
            String url = GEOCODE_URL + "?key=" + amapApiKey + "&address=" +
                    java.net.URLEncoder.encode(address, "UTF-8");
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                return null;
            }
            JSONObject json = JSONUtil.parseObj(resp.getBody());
            if (!"1".equals(json.getStr("status"))) {
                return null;
            }
            JSONArray geocodes = json.getJSONArray("geocodes");
            if (geocodes == null || geocodes.isEmpty()) {
                return null;
            }
            String location = geocodes.getJSONObject(0).getStr("location");
            if (location == null || !location.contains(",")) {
                return null;
            }
            String[] parts = location.split(",");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception e) {
            log.warn("高德地理编码失败: address={}", address, e);
            return null;
        }
    }

    // ==================== POI 周边搜索 ====================

    private List<AmapPoi> searchAroundCinemas(double lng, double lat, int radius) {
        try {
            String url = AROUND_SEARCH_URL
                    + "?key=" + amapApiKey
                    + "&location=" + lng + "," + lat
                    + "&keywords=电影院|影院|影城"
                    + "&types=060400"          // 娱乐休闲-电影院
                    + "&radius=" + radius
                    + "&offset=20"
                    + "&page=1"
                    + "&extensions=all";

            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                return List.of();
            }
            JSONObject json = JSONUtil.parseObj(resp.getBody());
            if (!"1".equals(json.getStr("status"))) {
                return List.of();
            }
            JSONArray pois = json.getJSONArray("pois");
            if (pois == null || pois.isEmpty()) {
                return List.of();
            }

            List<AmapPoi> result = new ArrayList<>();
            for (int i = 0; i < pois.size(); i++) {
                JSONObject poi = pois.getJSONObject(i);

                // ★ 类型校验：只保留影院相关 POI（typecode 以 06 开头 = 娱乐休闲大类）
                String typecode = poi.getStr("typecode", "");
                if (!isCinemaType(typecode)) {
                    log.debug("Amap POI 非影院类型，跳过: name={}, typecode={}", poi.getStr("name"), typecode);
                    continue;
                }

                String[] loc = poi.getStr("location", "").split(",");
                double pLng = loc.length == 2 ? Double.parseDouble(loc[0]) : 0;
                double pLat = loc.length == 2 ? Double.parseDouble(loc[1]) : 0;
                result.add(new AmapPoi(
                        poi.getStr("name"),
                        poi.getStr("address"),
                        poi.getStr("cityname"),
                        poi.getStr("adname"),
                        pLng,
                        pLat,
                        Integer.parseInt(poi.getStr("distance", "0")),
                        poi.getStr("tel", "")
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("高德POI搜索失败", e);
            return List.of();
        }
    }

    // ==================== 数据库匹配 ====================

    private List<Map<String, Object>> matchWithDatabase(
            List<AmapPoi> amapPois, Long filmId, double centerLng, double centerLat, int radius) {

        // 加载所有已发布影院
        List<Cinema> allCinemas = cinemaMapper.selectListByQuery(
                QueryWrapper.create().eq(Cinema::getStatus, "published"));

        // 如果有 filmId，获取排片影院集合
        Set<Long> scheduledCinemaIds = Set.of();
        if (filmId != null) {
            scheduledCinemaIds = scheduleMapper.selectListByQuery(
                            QueryWrapper.create()
                                    .select(Schedule::getCinemaId)
                                    .eq(Schedule::getFilmId, filmId)
                                    .eq(Schedule::getStatus, "published")
                                    .groupBy(Schedule::getCinemaId))
                    .stream()
                    .map(Schedule::getCinemaId)
                    .collect(Collectors.toSet());
        }

        // 匹配 Amap POI → DB Cinema
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Long> matchedDbIds = new HashSet<>();

        for (AmapPoi poi : amapPois) {
            Cinema matched = findBestMatch(poi, allCinemas);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("amapName", poi.name);
            map.put("address", poi.address);
            map.put("city", poi.city);
            map.put("district", poi.district);
            map.put("lng", poi.lng);
            map.put("lat", poi.lat);
            map.put("distanceMeters", poi.distance);
            map.put("distanceText", GeoUtils.formatDistance(poi.distance));
            map.put("phone", poi.tel);

            if (matched != null) {
                matchedDbIds.add(matched.getId());
                map.put("matched", true);
                map.put("cinemaId", matched.getId());
                map.put("name", matched.getName());           // ★ 前端兼容字段
                map.put("cinemaName", matched.getName());
                map.put("dbAddress", matched.getAddress());
                map.put("tags", matched.getTags());
                map.put("basePrice", matched.getBasePrice());
                boolean hasSchedule = filmId == null || scheduledCinemaIds.contains(matched.getId());
                map.put("hasSchedule", hasSchedule);
            } else {
                map.put("matched", false);
                map.put("cinemaId", null);
                map.put("name", poi.name);                    // ★ 未匹配时用 Amap 名称
                map.put("hasSchedule", false);
            }

            result.add(map);
        }

        // 补充：DB 中有但 Amap 没匹配上的附近影院（按距离过滤）
        // ★ Amap 数据可能滞后，DB 中有坐标的影院通过距离判断是否在附近
        int maxSupplementDist = Math.max(radius, 10000); // 补充上限：取 radius 和 10km 的较大值
        for (Cinema cinema : allCinemas) {
            if (matchedDbIds.contains(cinema.getId())) continue;

            // ★ 用距离替代城市匹配：只纳入真正在附近的影院
            if (cinema.getLongitude() == null || cinema.getLatitude() == null) continue;
            double cLng = cinema.getLongitude().doubleValue();
            double cLat = cinema.getLatitude().doubleValue();
            int distMeters = GeoUtils.haversineMetersInt(centerLat, centerLng, cLat, cLng);
            if (distMeters > maxSupplementDist) continue; // 超出补充范围，跳过

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("amapName", cinema.getName());
            map.put("name", cinema.getName());
            map.put("address", cinema.getAddress());
            map.put("city", cinema.getCity());
            map.put("matched", true);
            map.put("cinemaId", cinema.getId());
            map.put("cinemaName", cinema.getName());
            map.put("dbAddress", cinema.getAddress());
            map.put("tags", cinema.getTags());
            map.put("basePrice", cinema.getBasePrice());
            map.put("lng", cLng);
            map.put("lat", cLat);
            map.put("distanceMeters", distMeters);
            map.put("distanceText", GeoUtils.formatDistance(distMeters));

            boolean hasSchedule = filmId == null || scheduledCinemaIds.contains(cinema.getId());
            map.put("hasSchedule", hasSchedule);
            result.add(map);
        }

        // ★ 有 filmId 时只展示有排片的影院，避免卡片与 LLM 回复矛盾
        if (filmId != null) {
            result = result.stream()
                    .filter(m -> Boolean.TRUE.equals(m.get("hasSchedule")))
                    .collect(Collectors.toList());
        }

        // ★ 按距离从近到远排序
        result.sort(Comparator.comparingInt(m ->
                ((Number) m.getOrDefault("distanceMeters", Integer.MAX_VALUE)).intValue()));

        return result;
    }

    /**
     * 将 Amap POI 名称与 DB 影院名称进行模糊匹配
     */
    private Cinema findBestMatch(AmapPoi poi, List<Cinema> cinemas) {
        Cinema best = null;
        int bestScore = 0;

        for (Cinema cinema : cinemas) {
            String dbName = cinema.getName();
            if (dbName == null) continue;

            int score = nameMatchScore(poi.name, dbName);
            if (score > bestScore) {
                bestScore = score;
                best = cinema;
            }
        }
        return bestScore >= 1 ? best : null;  // 至少有一个关键词匹配
    }

    /**
     * 名称匹配得分（优化中文名称匹配）
     * - 完全匹配 → 100
     * - 包含关系 → 50
     * - 去除常见后缀后包含/相等 → 80
     * - 中文 bigram 交集得分 → bigram 命中数
     * - 关键词交集 → 关键词数
     */
    private int nameMatchScore(String amapName, String dbName) {
        if (amapName == null || dbName == null) return 0;

        String a = amapName.trim();
        String d = dbName.trim();

        if (a.equals(d)) return 100;
        if (a.contains(d) || d.contains(a)) return 50;

        // 去除常见后缀和修饰后匹配
        // ★ 更激进的清洗：品牌名后面的分店标注 (XX路/XX店/XX广场/XX号)、影院后缀、括号内容
        String suffixPattern = "[（(]?影城|电影院|影院|影剧院|[）)]"
                + "|[（(][^）)]*[）)]"        // 括号及内容
                + "|[\\u4e00-\\u9fa5]{1,4}店"   // XX店
                + "|[\\u4e00-\\u9fa5]{0,4}[路街]\\d{0,4}号?" // XX路/XX街
                + "|[\\u4e00-\\u9fa5]{0,6}广场"   // XX广场
                + "|\\d+号";                       // XX号
        String aClean = a.replaceAll(suffixPattern, "").trim();
        String dClean = d.replaceAll(suffixPattern, "").trim();

        // 清洗后完全匹配或包含
        if (aClean.equals(dClean) || (aClean.length() > 1 && dClean.length() > 1
                && (aClean.contains(dClean) || dClean.contains(aClean)))) {
            return 80;
        }

        // ★ 中文 bigram 交集（解决分词失败的问题）
        if (aClean.length() >= 2 && dClean.length() >= 2) {
            Set<String> aBi = charNgrams(aClean, 2);
            Set<String> dBi = charNgrams(dClean, 2);
            Set<String> intersection = new HashSet<>(aBi);
            intersection.retainAll(dBi);
            int biHits = intersection.size();
            int minLen = Math.min(aClean.length(), dClean.length());
            // bigram 命中率高 → 很可能是同一家
            if (biHits >= 3 && biHits >= minLen * 0.4) return 60;
            if (biHits >= 2) return biHits * 10; // 2个匹配→20, 3个→30...
        }

        // 关键词匹配（空格/特殊符号分词，对英文名有效）
        Set<String> aWords = new HashSet<>(Arrays.asList(aClean.split("[\\s·\\-—]+")));
        Set<String> dWords = new HashSet<>(Arrays.asList(dClean.split("[\\s·\\-—]+")));
        aWords.retainAll(dWords);
        return aWords.size();
    }

    /** 判断 Amap POI 类型码是否为影院 */
    private static boolean isCinemaType(String typecode) {
        if (typecode == null || typecode.isBlank()) return false;
        // 060400=电影院, 060410=影剧院 — 都属于影院相关类型
        return typecode.startsWith("0604");
    }

    /** 生成中文 n-gram */
    private Set<String> charNgrams(String s, int n) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i <= s.length() - n; i++) {
            set.add(s.substring(i, i + n));
        }
        return set;
    }

    // ==================== 写回 State ====================

    private void writeBackToState(List<Map<String, Object>> matchedList) {
        String convId = ConversationContext.get();
        if (convId == null) return;

        try {
            // 仅当只有一个匹配结果时自动选定影院
            List<Map<String, Object>> matched = matchedList.stream()
                    .filter(m -> Boolean.TRUE.equals(m.get("matched")) && m.get("cinemaId") != null)
                    .toList();
            if (matched.size() == 1) {
                ConversationState state = stateManager.getState(convId);
                if (state.getCinemaId() == null) {
                    Map<String, Object> only = matched.get(0);
                    state.setCinemaId(((Number) only.get("cinemaId")).longValue());
                    state.setCinemaName((String) only.get("cinemaName"));
                    stateManager.saveState(convId, state);
                    log.info("SearchNearbyCinemas 写回 cinemaId={}: convId={}",
                            state.getCinemaId(), convId);
                }
            }
        } catch (Exception e) {
            log.warn("SearchNearbyCinemas 写回状态失败: convId={}", convId, e);
        }
    }

    /** Amap POI 数据对象 */
    private record AmapPoi(
            String name,
            String address,
            String city,
            String district,
            double lng,
            double lat,
            int distance,
            String tel
    ) {}

    @Override
    public String getToolName() {
        return "searchNearbyCinemas";
    }

    @Override
    public String getDisplayName() {
        return "搜索附近影院";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String location = arguments.getStr("location");
        return String.format("[工具调用] 搜索附近影院 location=%s", location);
    }
}
