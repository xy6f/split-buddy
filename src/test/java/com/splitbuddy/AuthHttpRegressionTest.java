package com.splitbuddy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证链路 HTTP 回归测试（全量测试 A 组，端到端真实过滤器链 + 真实 MySQL）。
 *
 * <p>客户端刻意用 JDK java.net.http.HttpClient 而不是 TestRestTemplate：
 * 后者底层的 HttpURLConnection 在"流模式 POST + 401"下读不到响应体
 * （cannot retry due to server authentication，属测试客户端缺陷，
 * 服务端行为已由 curl 实测正确），HttpClient 无此问题。</p>
 *
 * <p>覆盖两条"案发现场"：</p>
 * <ol>
 *   <li>A1：用户名含下划线注册——历史嫌疑"触发 50000 未处理异常"。
 *       根因通道是 Git Bash 中文 payload 转 GBK 后的 JSON 解析异常被兜底分支
 *       翻译成 500（下划线本身在 ASCII 下完全合法）；HttpMessageNotReadable
 *       专属 handler 修复后，下划线注册必须干净返回 200/code 0；</li>
 *   <li>A6/A7：无令牌/伪造令牌访问受保护接口——SecurityConfig 补装
 *       401 authenticationEntryPoint 后必须返回 401 + JSON（曾默认 403 空体）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthHttpRegressionTest {

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void underscoreUsernameRegistersCleanly() throws Exception {
        // A1 回归：ASCII 下划线用户名，200 + code 0，绝不 500/50000
        String u = "qa_it_underscore_" + System.nanoTime();
        HttpResponse<String> ok = post("/api/auth/register",
                "{\"username\":\"" + u + "\",\"password\":\"123456\",\"nickname\":\"Under_Score\"}");
        assertEquals(200, ok.statusCode());
        assertTrue(ok.body().contains("\"code\":0"), ok.body());

        // A2 回归：重复注册 409 + 40901（而不是 200 假成功）
        HttpResponse<String> dup = post("/api/auth/register",
                "{\"username\":\"" + u + "\",\"password\":\"123456\",\"nickname\":\"Again\"}");
        assertEquals(409, dup.statusCode());
        assertTrue(dup.body().contains("\"code\":40901"), dup.body());
    }

    @Test
    void badCredentialPathsReturnProperStatus() throws Exception {
        String u = "qa_it_login_" + System.nanoTime();
        assertEquals(200, post("/api/auth/register",
                "{\"username\":\"" + u + "\",\"password\":\"123456\",\"nickname\":\"Lg\"}").statusCode());

        // A4 回归：错密码登录 401 + 40101（POST+401 正是 TestRestTemplate 踩坑场景）
        HttpResponse<String> wrong = post("/api/auth/login",
                "{\"username\":\"" + u + "\",\"password\":\"badpass\"}");
        assertEquals(401, wrong.statusCode());
        assertTrue(wrong.body().contains("\"code\":40101"), wrong.body());

        // A3 回归：弱密码注册 400
        assertEquals(400, post("/api/auth/register",
                "{\"username\":\"qa_it_weak_" + System.nanoTime() + "\",\"password\":\"1\",\"nickname\":\"W\"}").statusCode());

        // 历史嫌疑通道回归：非法 UTF-8（模拟 Git Bash GBK 中文 payload）必须 400 而非 500/50000
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{'{', (byte) 0xB2, (byte) 0xCA, '}'}))
                .build();
        assertEquals(400, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void anonymousAndForgedTokenGet401JsonBody() throws Exception {
        // A6 回归：无令牌 401（修复前是 403 空响应体）
        HttpResponse<String> noToken = get("/api/ledgers", null);
        assertEquals(401, noToken.statusCode());
        assertTrue(noToken.body().contains("\"code\":40100"), noToken.body());

        // A7 回归：伪造令牌同样 401（JwtAuthFilter 解析失败保持匿名 -> 401 入口点兜底）
        HttpResponse<String> bad = get("/api/ledgers", "header.payload.forgesig");
        assertEquals(401, bad.statusCode());
        assertTrue(bad.body().contains("\"code\":40100"), bad.body());
    }
}
