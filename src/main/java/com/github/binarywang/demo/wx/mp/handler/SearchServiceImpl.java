package com.github.binarywang.demo.wx.mp.handler;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.github.binarywang.demo.wx.mp.model.DownResponse;
import com.github.binarywang.demo.wx.mp.model.FolderRes;
import com.github.binarywang.demo.wx.mp.model.WebResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Document;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * search service
 */
@Slf4j
public class SearchServiceImpl {

    static int time_out = 4000;

    static String def_mv = "电影仓：https://www.aliyundrive.com/s/tBiAuhLpanb";
    static String def_ds = "电视剧仓：https://www.aliyundrive.com/s/BjzGgQ4QLTd/folder/6242a5ca02dcaa56224d464d8980789e98a42b67";

    static String res_hongbao = "";

    static String nores_hongbao = "";

    static String resp_head = res_hongbao + System.lineSeparator();


    static String defRes = "抱歉，未找到包含关键字的资源，给你其他好看的："
            + System.lineSeparator() + def_mv
            + System.lineSeparator() + def_ds
            + System.lineSeparator() + nores_hongbao;

    static String base_url = "https://api.upyunso2.com/";

    static String start_url = "https://api.upyunso2.com/search?page=1&s_type=2&keyword=";

    static int res_limit = 10;

    static String lineSp = System.lineSeparator();

    static ExecutorService executorService = Executors.newCachedThreadPool();

    static Cache<String, String> resCache = CacheBuilder.newBuilder()
            //cache的初始容量
            .initialCapacity(20)
            //cache最大缓存数
            .maximumSize(200)
            //设置写缓存后n秒钟过期
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();

    public static String searchByKeyword(String keyword) {
        // 获取value的值，如果key不存在，调用collable方法获取value值加载到key中再返回
        // 删除关键词中的空格
        String noSpaceKeyWord = org.apache.commons.lang3.StringUtils.deleteWhitespace(keyword);
        String res = resCache.getIfPresent(noSpaceKeyWord);
        return StringUtils.isEmpty(res) ? getResFromWeb(noSpaceKeyWord) : res;

    }

    public static String invokeApi(int invokeType, String keyword) {
        Document document;
        String searchUrl = "";
        try {
            if (invokeType == 1) {
                String encodeKeyword = URLEncoder.encode(keyword, "utf-8");
                searchUrl = start_url + encodeKeyword;
            } else {
                searchUrl = base_url + keyword;
            }
            Connection conn = Jsoup.connect(searchUrl);
            conn.header("Accept", "*/*");
            conn.header("Referer", "https://www.upyunso.com/");
            conn.header("Origin", "https://www.upyunso.com");
            conn.header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1");
            conn.timeout(time_out);
            document = conn.get();
        } catch (Exception e) {
            log.error("search error", e);
            return null;
        }

        final Base64.Decoder decoder = Base64.getDecoder();
        return new String(decoder.decode(document.text()), StandardCharsets.UTF_8);

    }

    public static String getDirectUrl(String keyword, List<FolderRes> elements) {
        StringBuilder resultStr = new StringBuilder(resp_head + "包含[" + keyword + "]的资源：");
        log.debug("searching keyword: {}", keyword);
        int count = 0;
        for (FolderRes element : elements) {
            if (count >= res_limit) break;
            // add the new url
            String resUrl = element.getPage_url();
            String resID = resUrl.substring(resUrl.lastIndexOf("/") + 1);
            if (resUrl.contains("aliyundrive") && !resultStr.toString().contains(resID)) {
                count++;
                resultStr.append(getPath(element.getPath(), keyword)).append(":").append(element.getPage_url()).append(lineSp);
            }
        }

        if (count == 0) return null;
        // add to cache
        resCache.put(keyword, resultStr.toString());
        // response
        return resultStr.toString();
    }

    public static boolean isValid(String resUrl) {
//        Connection conn = Jsoup.connect(resUrl);
//        conn.header("Accept", "*/*");
//        conn.header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1");
//        conn.timeout(time_out);
        try {
//            Document document = conn.get();
//            Document document = Jsoup.parse(new URL(resUrl), time_out);
            Connection.Response resp = Jsoup.connect(resUrl)
                    .header("access-control-allow-credentials", "true")
                    .header("access-control-allow-origin", "https://www.aliyundrive.com")
                    .header("access-control-expose-headers", "Content-MD5,X-Request-Id,X-Canary,X-Share-Token,X-Ca-Request-Id,X-Ca-Error-Code,X-Ca-Error-Message")
                    .header("content-type", "application/json")
                    .timeout(1000)
                    .method(Connection.Method.OPTIONS)
                    .maxBodySize(0)
                    .followRedirects(false)
                    .execute();
            String htmlStr = new String(resp.bodyAsBytes());
            System.out.println(htmlStr);
            return true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * remove the duplicated string in resource path
     *
     * @param initialPath
     * @param keyword
     * @return
     */
    public static String getPath(String initialPath, String keyword) {
        String regex = "(" + keyword + ")\\1+";
        String deduplicate = initialPath.replaceAll(regex, "$1");
        // 如果过长就返回查询的关键字
        return StringEscapeUtils.escapeHtml4(deduplicate.length() > 30 ? keyword : deduplicate);
    }

    public static String getResFromWeb(String keyword) {

        List<FolderRes> elements = getUrlList(keyword);
        if (elements == null || elements.size() <= 0) {
            return defRes;
        }

        if (getDirectUrl(keyword, elements) != null) {
            return getDirectUrl(keyword, elements);
        }

        // 多线程异步查找
        executorService.submit(() -> {
            try {
                updateCache(keyword, elements);
            } catch (InterruptedException e) {
                log.error("update resource cache error", e);
            }
        });

        // 先返回第一个
        StringBuilder resStr = new StringBuilder("包含[ " + keyword + " ]的第一个资源：" + lineSp);
        // return top resource
        String downUrl = "";
        for (FolderRes element : elements) {
            if (element.getPage_url().contains("download")) {
                downUrl = element.getPage_url();
                break;
            }
        }
        if (downUrl.length() == 0) {
            return defRes;
        }
        String resUrl = getResUrl(downUrl);
        if (StringUtil.isBlank(resUrl)) {
            return defRes;
        }
        resStr.append(resUrl).append(lineSp).append(lineSp).append("请再次发送消息获取更多资源");
        return resStr.toString();
    }

    private static void updateCache(String keyword, List<FolderRes> elements) throws InterruptedException {
        log.debug("start {}", System.currentTimeMillis());
        Set<String> urlSet = new CopyOnWriteArraySet<>();
        Set<String> nameSet = new CopyOnWriteArraySet<>();
        StringBuilder resStr = new StringBuilder("包含[ " + keyword + " ]的资源：" + lineSp);
        CountDownLatch latch = new CountDownLatch(res_limit);
        for (FolderRes element : elements) {
            if (urlSet.size() < res_limit) {
                String downUrl = element.getPage_url();
                if (!downUrl.contains("download")) {
                    continue;
                }
                executorService.submit(() -> {
                    String resUrl = getResUrl(downUrl);
                    if (!StringUtil.isBlank(resUrl) && !nameSet.contains(element.getPath()) && !urlSet.contains(resUrl) && urlSet.size() < res_limit) {
                        urlSet.add(resUrl);
                        nameSet.add(element.getPath());
                        resStr.append(StringEscapeUtils.escapeHtml4(element.getPath())).append(":").append(resUrl).append(lineSp);
                        latch.countDown();
                    }
                });
                Thread.sleep(350);
            } else {
                break;
            }
        }
        log.debug("threads started {}", System.currentTimeMillis());
        boolean done = latch.await(1000, TimeUnit.MILLISECONDS);
        log.debug("threads end {}", done);
        String res = urlSet.size() > 0 ? resStr.toString() : defRes;
        log.debug("res is {}", res);
        resCache.put(keyword, res);
    }

    private static String getParam(String url) {
        return url.replace(".html", "");
    }

    public static String getResUrl(String resId) {

        if (resId.contains("aliyundrive")) {
            return resId;
        }

        String downDoc = invokeApi(2, getParam(resId));
//        String downDoc = invokeApi(2, resId);
        if (StringUtil.isBlank(downDoc)) {
            return "";
        }
        final DownResponse downResponse = new Gson().fromJson(downDoc, DownResponse.class);
        return downResponse.getResult().getRes_url();
    }

    public static List<FolderRes> getUrlList(String keyword) {
        String docStr = invokeApi(1, keyword);
        if (StringUtil.isBlank(docStr)) {
            return null;
        }
        WebResponse response = new Gson().fromJson(docStr, WebResponse.class);
        return response.getResult().getItems();
    }

    public static Document getDocument(String webUrl) {
//        ChromeDriver driver = new ChromeDriver();
////            driver.setJavascriptEnabled(true);
//        driver.get(webUrl);
//        System.out.println(driver.toString());
        return null;
    }

    public static void main(String[] args) {
//        isValid("https://api.aliyundrive.com/adrive/v3/share_link/get_share_by_anonymous?share_id=B1hRjhZEzJG");
//        System.out.println(getDocument("https://www.aliyundrive.com/s/B1hRjhZEzJG").toString());
//        Document document = null;
//        String keyword = "昆仑神宫";
//        try {
//            document = Jsoup.parse(new URL(start_url + keyword), time_out);
////            System.out.println(document);
//            getResFromWeb(keyword);
//        } catch (Exception e) {
//            log.error("search error", e);
//        }
//        assert document != null;
//        final Base64.Decoder decoder = Base64.getDecoder();
//        System.out.println("decode:"+new String(decoder.decode(document.text()), StandardCharsets.UTF_8));
//        Elements resList = document.select("div.main-info > h1 > a");
        String test = "https://www.aliyundrive.com/s/seSdtWsrQdk/folder/63e2259a57e4df254e00458bbdc676ace2ea49fb";
        System.out.println(test.substring(test.lastIndexOf("/") + 1));
        String keyword = "狂飙";
        String regex = "(" + keyword + ")\\1+";
        String target = test.replaceAll(regex, "$1");
        System.out.println(target);
    }

}
