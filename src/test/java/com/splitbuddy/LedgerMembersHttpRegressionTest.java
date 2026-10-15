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
 * 成员名单接口 GET /api/ledgers/{id}/members 的 HTTP 回归测试
 * （沿用 AuthHttpRegressionTest 风格：真实过滤器链 + 真实 MySQL，JDK HttpClient）。
 *
 * <p>覆盖三件事：</p>
 * <ol>
 *   <li>M1：两人账本（owner + 被邀请者）名单返回 2 条，nickname/joinedAt 齐全——
 *       重点验证 join t_ledger_member + t_user 的构造器自动映射真的取出了数据，
 *       而不是静默返回空列表/全 null；</li>
 *   <li>M2：被邀请者本人也能查名单（成员权限对称，不只是 owner 特权）；</li>
 *   <li>M3：非成员查询 403 + 40300、账本不存在 404 + 40400——
 *       assertMember 的两种失败语义在名单接口上同样成立（权限在 Service 第一行）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerMembersHttpRegressionTest {

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 注册并登录，返回令牌（注册即登录语义：MVP 里 register 后直接 login 拿 token） */
    private String registerAndLogin(String username, String nickname) throws Exception {
        HttpResponse<String> reg = post("/api/auth/register",
                "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"" + nickname + "\"}", null);
        assertEquals(200, reg.statusCode(), reg.body());
        HttpResponse<String> login = post("/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"123456\"}", null);
        assertEquals(200, login.statusCode(), login.body());
        // 从 {"code":0,"data":{"token":"..." ...}} 里取 token：定位 "token":" 后截到下一个引号
        String body = login.body();
        int i = body.indexOf("\"token\":\"");
        assertTrue(i >= 0, body);
        int j = body.indexOf('"', i + 9);
        return body.substring(i + 9, j);
    }

    @Test
    void membersListReturnsBothUsersWithJoinedAt() throws Exception {
        String tag = "qa_it_mem_" + System.nanoTime();
        String alice = tag + "_a", bob = tag + "_b", carol = tag + "_c";
        String tokenA = registerAndLogin(alice, "Alice张");
        String tokenB = registerAndLogin(bob, "Bob李");
        String tokenC = registerAndLogin(carol, "Carol王");

        // 建账本 + 邀请
        HttpResponse<String> created = post("/api/ledgers", "{\"name\":\"qa members 回归账本\"}", tokenA);
        assertEquals(200, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"code\":0"), created.body());
        int li = created.body().indexOf("\"id\":");
        assertTrue(li >= 0, created.body());
        long ledgerId = Long.parseLong(created.body().substring(li + 5).split("[^0-9]")[0]);

        HttpResponse<String> inv = post("/api/ledgers/" + ledgerId + "/members",
                "{\"username\":\"" + bob + "\"}", tokenA);
        assertEquals(200, inv.statusCode(), inv.body());

        // M1：owner 视角——2 条记录，昵称/加入时间齐全（join 映射有效性在这里兜底验证）
        HttpResponse<String> listA = get("/api/ledgers/" + ledgerId + "/members", tokenA);
        assertEquals(200, listA.statusCode(), listA.body());
        String bodyA = listA.body();
        assertTrue(bodyA.contains("\"code\":0"), bodyA);
        assertTrue(bodyA.contains("Alice张"), bodyA);
        assertTrue(bodyA.contains("Bob李"), bodyA);
        assertEquals(2, countOccurrences(bodyA, "\"joinedAt\""), bodyA);
        assertEquals(2, countOccurrences(bodyA, "\"userId\""), bodyA);

        // M2：被邀请者本人也可查，内容一致
        HttpResponse<String> listB = get("/api/ledgers/" + ledgerId + "/members", tokenB);
        assertEquals(200, listB.statusCode(), listB.body());
        assertTrue(listB.body().contains("Alice张"), listB.body());

        // M3：非成员 403/40300；不存在的账本 404/40400（assertMember 两种失败语义）
        HttpResponse<String> forb = get("/api/ledgers/" + ledgerId + "/members", tokenC);
        assertEquals(403, forb.statusCode(), forb.body());
        assertTrue(forb.body().contains("\"code\":40300"), forb.body());

        HttpResponse<String> nf = get("/api/ledgers/99999999/members", tokenA);
        assertEquals(404, nf.statusCode(), nf.body());
        assertTrue(nf.body().contains("\"code\":40400"), nf.body());

        // 无令牌访问受保护名单：401（走认证入口点，与 A6 同语义）
        assertEquals(401, get("/api/ledgers/" + ledgerId + "/members", null).statusCode());
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }
}
