package com.rsshub.adminapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Batch subscription (F16): the paste-many-addresses button, at API level.
 *
 * <p>Two properties of the design decide what is worth asserting here. The plan writes nothing and the write
 * re-reads the text itself, so the preview cannot be turned into an instruction — a caller cannot rename a line into
 * an address the guard never looked at. And a batch fetches nothing: it stores the address as given, the way an OPML
 * import does, which is why an unreachable loopback port still subscribes cleanly. The dispatcher is off, so the
 * queue is checked in SQL — that is what "the paste armed each new feed" means.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:subbatch;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.ssrf.allow-private-prefixes[0]=127.0.0.1"
})
@AutoConfigureMockMvc
class SubscriptionBatchApiTest {

    private static final String PASSWORD = "a-test-only-password";

    /** Nothing listens here; a batch must subscribe it anyway because a batch never fetches. */
    private static final String DEAD = "http://127.0.0.1:1/dead.rss";
    private static final String FIRST = "http://127.0.0.1/one.rss";
    private static final String SECOND = "http://127.0.0.1/two.rss";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    ObjectMapper json;

    private MockHttpSession session;

    @BeforeEach
    void login() throws Exception {
        db.sql("delete from entry_revision").update();
        db.sql("delete from entry").update();
        db.sql("delete from raw_response").update();
        db.sql("delete from scheduled_task").update();
        db.sql("delete from feed").update();

        session = new MockHttpSession();
        Cookie csrf = csrf();
        mvc.perform(post("/api/auth/login").session(session)
                .param("username", "admin").param("password", PASSWORD)
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-SUB-61 解析多行粘贴：每行一条，且不写库")
    void planSplitsEveryLineAndWritesNothing() throws Exception {
        JsonNode body = read(mvc.perform(write(post("/api/feeds/batch/plan"),
            Map.of("text", FIRST + "\n" + SECOND + ", " + DEAD))));

        assertThat(body.get("ready").asInt()).isEqualTo(3);
        assertThat(items(body)).extracting(node -> node.get("address").asString())
            .containsExactly(FIRST, SECOND, DEAD);
        assertThat(items(body)).extracting(node -> node.get("lineNo").asInt()).containsExactly(1, 2, 2);
        assertThat(count("feed")).isZero();
    }

    @Test
    @DisplayName("TC-SUB-62 标题写在地址前面时成为订阅名")
    void aNameInFrontOfAnAddressBecomesTheCustomTitle() throws Exception {
        JsonNode result = read(mvc.perform(write(post("/api/feeds/batch"),
            Map.of("text", "一号: " + FIRST + "\n" + SECOND, "group", "批量"))));

        assertThat(result.get("added").asInt()).isEqualTo(2);
        assertThat(db.sql("select custom_title from feed where canonical_url = ?")
            .params(FIRST).query(String.class).single()).isEqualTo("一号");
        assertThat(db.sql("select count(*) from feed where canonical_url = ? and custom_title is null")
            .params(SECOND).query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from feed where group_name = ?")
            .params("批量").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-SUB-63 一次粘贴里的重复地址只订一次")
    void theSameAddressTwiceInOnePasteIsASubscriptionAndADuplicate() throws Exception {
        JsonNode body = read(mvc.perform(write(post("/api/feeds/batch/plan"),
            Map.of("text", FIRST + "\n" + FIRST))));

        assertThat(items(body).get(0).get("status").asString()).isEqualTo("ready");
        assertThat(items(body).get(1).get("status").asString()).isEqualTo("duplicate");
        assertThat(items(body).get(1).get("reason").asString()).isEqualTo("already_in_paste");
        assertThat(body.get("ready").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-SUB-64 已订阅的地址算重复，整批仍返回 200")
    void anAlreadySubscribedAddressIsReportedRatherThanRejected() throws Exception {
        read(mvc.perform(write(post("/api/feeds/batch"), Map.of("text", FIRST))));

        JsonNode again = read(mvc.perform(write(post("/api/feeds/batch"),
            Map.of("text", FIRST + "\n" + SECOND))));

        assertThat(again.get("added").asInt()).as("200 with one line skipped, not a 409").isEqualTo(1);
        assertThat(items(again).get(0).get("status").asString()).isEqualTo("duplicate");
        assertThat(items(again).get(0).get("reason").asString()).isEqualTo("already_subscribed");
        assertThat(items(again).get(1).get("status").asString()).isEqualTo("added");
        assertThat(count("feed")).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-SUB-65 每条新订阅排一次抓取，按顺序错开")
    void eachNewSubscriptionGetsItsOwnStaggeredFetchRow() throws Exception {
        read(mvc.perform(write(post("/api/feeds/batch"), Map.of("text", FIRST + "\n" + SECOND + "\n" + DEAD))));

        assertThat(count("scheduled_task")).isEqualTo(3);
        List<Instant> fireAts = db.sql("select fire_at from scheduled_task where kind = 'fetch' order by fire_at")
            .query((ResultSet rs, int rowNum) -> rs.getTimestamp(1).toInstant()).list();
        assertThat(Instant.now()).isAfterOrEqualTo(fireAts.get(0));
        // Ten seconds apart, so a fifty-line paste does not open fifty sockets at once (the OPML import's rule).
        assertThat(Duration.between(fireAts.get(0), fireAts.get(1))).isEqualTo(Duration.ofSeconds(10));
        assertThat(Duration.between(fireAts.get(1), fireAts.get(2))).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("TC-SUB-66 批量订阅不发请求：没人监听的地址照样入库")
    void aBatchNeverTouchesTheNetwork() throws Exception {
        JsonNode result = read(mvc.perform(write(post("/api/feeds/batch"), Map.of("text", DEAD))));

        assertThat(items(result).get(0).get("status").asString()).isEqualTo("added");
        assertThat(db.sql("select canonical_url from feed").query(String.class).single()).isEqualTo(DEAD);
        assertThat(db.sql("select requested_url from feed").query(String.class).single()).isEqualTo(DEAD);
    }

    @Test
    @DisplayName("TC-SUB-67 内网地址被 SSRF 规则拦下并写明原因")
    void aPrivateTargetIsRefusedAndSaysWhy() throws Exception {
        JsonNode body = read(mvc.perform(write(post("/api/feeds/batch/plan"),
            Map.of("text", "http://192.168.7.7/inside.rss\n" + FIRST))));

        assertThat(items(body).get(0).get("status").asString()).isEqualTo("refused");
        assertThat(items(body).get(0).get("reason").asString()).startsWith("private_target");
        assertThat(body.get("ready").asInt()).isEqualTo(1);

        JsonNode written = read(mvc.perform(write(post("/api/feeds/batch"),
            Map.of("text", "http://192.168.7.7/inside.rss\n" + FIRST))));
        assertThat(written.get("added").asInt()).isEqualTo(1);
        assertThat(count("feed")).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-SUB-68 非 http(s) 协议算无效，不是拒绝")
    void aNonHttpSchemeIsInvalidRatherThanRefused() throws Exception {
        JsonNode body = read(mvc.perform(write(post("/api/feeds/batch/plan"),
            Map.of("text", "ftp://files.example/rss"))));

        assertThat(items(body).get(0).get("status").asString()).isEqualTo("invalid");
        assertThat(items(body).get(0).get("reason").asString()).isEqualTo("scheme_not_allowed");
        assertThat(body.get("ready").asInt()).isZero();
    }

    @Test
    @DisplayName("TC-SUB-69 读不懂的一行不静默丢弃，仍占一行")
    void anUnreadableLineIsStillShownAsItsOwnRow() throws Exception {
        JsonNode body = read(mvc.perform(write(post("/api/feeds/batch/plan"),
            Map.of("text", "见备注\n" + FIRST))));

        assertThat(items(body)).hasSize(2);
        assertThat(items(body).get(0).get("status").asString()).isNotEqualTo("ready");
        assertThat(items(body).get(0).get("address").asString()).isEqualTo("见备注");
    }

    @Test
    @DisplayName("TC-SUB-70 超过 200 条整批拒绝，且不写半批")
    void aPasteBiggerThanTheCapIsRefusedOutOfHand() throws Exception {
        String text = IntStream.range(0, 201)
            .mapToObj(i -> "http://127.0.0.1/feed-" + i + ".rss")
            .reduce((a, b) -> a + "\n" + b)
            .orElseThrow();

        mvc.perform(write(post("/api/feeds/batch"), Map.of("text", text)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:too_many_lines"));
        assertThat(count("feed")).isZero();
        assertThat(count("scheduled_task")).isZero();
    }

    @Test
    @DisplayName("TC-SUB-71 空粘贴被请求体契约挡下，读不出地址的粘贴被服务挡下")
    void aBlankPasteIsRejectedByTheRequestContract() throws Exception {
        mvc.perform(write(post("/api/feeds/batch/plan"), Map.of("text", "   ")))
            .andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/feeds/batch"), Map.of("text", "")))
            .andExpect(status().isBadRequest());

        // Separators alone survive `@NotBlank` but leave the splitter with nothing, which is the service's
        // `nothing_to_add` — an empty paste must never look like "subscribed 0 of 0" success.
        mvc.perform(write(post("/api/feeds/batch/plan"), Map.of("text", ",,;\n、")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:nothing_to_add"));
        assertThat(count("feed")).isZero();
    }

    @Test
    @DisplayName("TC-SUB-72 未登录不能解析也不能批量写入")
    void bothBatchEndpointsRequireAnOperatorSession() throws Exception {
        // 403 rather than 401: every write in this app meets the CSRF filter before the entry point does, and a
        // tokenless batch is refused there. What the test actually pins is that the refusal happens before the
        // service — nothing was subscribed by a caller who is not an operator.
        mvc.perform(post("/api/feeds/batch/plan").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text", FIRST))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/feeds/batch").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text", FIRST))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/feeds")).andExpect(status().isUnauthorized());
        assertThat(count("feed")).isZero();
    }

    private List<JsonNode> items(JsonNode body) {
        return body.get("items").valueStream().toList();
    }

    private int count(String table) {
        return db.sql("select count(*) from " + table).query(Integer.class).single();
    }

    private JsonNode read(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        assertThat(result.getResponse().getStatus())
            .as("body: %s", result.getResponse().getContentAsString())
            .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Map<String, Object> body)
        throws Exception {
        Cookie csrf = csrf();
        return request.session(session).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))
            .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private Cookie csrf() throws Exception {
        Cookie cookie = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
