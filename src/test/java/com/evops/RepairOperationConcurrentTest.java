package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复同一操作并发（museum-11）验收：
 * 1) 可重复操作（阶段登记）8 并发：全部生效，阶段号 1..8 不重不丢（行锁串行化）；
 * 2) 状态跃迁操作（修复完成）8 并发：恰 1 个 EFFECTIVE、7 个 REJECTED，
 *    被拒尝试指向胜出尝试，台账保留 7 条 REPAIR_OP_REJECT；
 * 3) 同一请求流水号 8 并发开工：只执行一次、只有一条尝试，全部幂等回放同一结果；
 * 4) 胜出流水号事后重放：不新增尝试、不二次执行。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-repair-concurrent;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairOperationConcurrentTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static final class Outcome {
        boolean success;
        String message;
        JsonNode data;
    }

    @Test
    void concurrent_repair_operations_name_effective_and_rejected_with_audit() throws Exception {
        long projectId = approvedProject("RC-001", "并发青铜鼎");
        // 会签通过后开工，项目进入 REPAIRING
        assertTrue(call(start(projectId, "FINISH-SETUP-0", "领班")).success);

        // ---- 场景 1：8 并发阶段登记（可重复操作）→ 全部生效，序号连续不丢 ----
        List<Outcome> stages = parallelStages(projectId, 8,
                i -> stageBody("STG-" + i, "修复师" + i));
        Set<Integer> stageNos = new HashSet<>();
        for (Outcome o : stages) {
            assertTrue(o.success, "阶段登记都应生效，实际: " + o.message);
            stageNos.add(o.data.get("stageNo").asInt());
        }
        assertEquals(8, stageNos.size(), "8 个并发阶段必须产生 8 个不同阶段号");
        for (int n = 1; n <= 8; n++) {
            assertTrue(stageNos.contains(n), "阶段号必须连续覆盖 1..8，缺: " + n);
        }
        JsonNode stageAttempts = attempts(projectId, "STAGE");
        assertEquals(8, stageAttempts.size());
        for (JsonNode a : stageAttempts) {
            assertEquals("EFFECTIVE", a.get("decision").asText());
        }

        // ---- 场景 2：8 并发修复完成（状态跃迁）→ 1 生效 7 拒绝 ----
        List<Outcome> finishes = parallelFinish(projectId, 8,
                i -> simpleBody("FINISH-" + i, "修复师" + i));
        int finishOk = 0;
        for (Outcome o : finishes) {
            if (o.success) {
                finishOk++;
                assertEquals("REPAIRED", o.data.get("status").asText());
            } else {
                assertTrue(o.message.contains("不能完成"),
                        "落败原因应为项目已不在修复中，实际: " + o.message);
            }
        }
        assertEquals(1, finishOk, "同一操作并发只允许一个生效");

        JsonNode finishAttempts = attempts(projectId, "FINISH");
        int effective = 0;
        long winnerId = -1;
        String winnerRequestNo = null;
        int rejected = 0;
        Set<Long> winnerRefs = new HashSet<>();
        for (JsonNode a : finishAttempts) {
            if ("EFFECTIVE".equals(a.get("decision").asText())) {
                effective++;
                winnerId = a.get("id").asLong();
                winnerRequestNo = a.get("requestNo").asText();
            } else {
                rejected++;
                assertFalse(a.get("effectiveAttemptId").isNull(),
                        "被拒尝试必须指向最终生效尝试");
                winnerRefs.add(a.get("effectiveAttemptId").asLong());
                assertTrue(a.get("rejectReason").asText().contains("不能完成"));
            }
        }
        assertEquals(1, effective);
        assertEquals(7, rejected);
        assertEquals(1, winnerRefs.size(), "7 条被拒记录应指向同一个胜出尝试");
        assertTrue(winnerRefs.contains(winnerId), "被拒记录指向的必须是实际胜出者");

        // 台账：保留 7 条并发拒绝流水，且能看出谁生效
        long relicId = projectDetail(projectId).get("project").get("relicId").asLong();
        int rejectLedger = 0;
        for (JsonNode e : dataOf(getJson("/api/relics/" + relicId + "/ledger"))) {
            if ("REPAIR_OP_REJECT".equals(e.get("action").asText())) {
                rejectLedger++;
                assertTrue(e.get("summary").asText().contains("最终生效为尝试#" + winnerId),
                        "台账须说明最终谁生效");
            }
        }
        assertEquals(7, rejectLedger, "每次被拒绝都要有独立审计台账");

        // ---- 场景 3：胜出流水号事后重放：不二次执行、不新增尝试 ----
        Outcome replay = call(postUri("/api/repair-projects/" + projectId + "/finish",
                simpleBody(winnerRequestNo, "修复师0")));
        assertTrue(replay.success, "同流水号重试应幂等回放原结果");
        assertEquals("REPAIRED", replay.data.get("status").asText());
        assertEquals(8, attempts(projectId, "FINISH").size(), "幂等重放不得新增尝试");

        // ---- 场景 4：另一项目，8 并发同一流水号开工：只执行一次 ----
        long project2 = approvedProject("RC-002", "并发陶碗");
        List<Outcome> sameStart = parallelPost(8,
                "/api/repair-projects/" + project2 + "/start",
                i -> simpleBody("START-SAME", "开工员"));
        for (Outcome o : sameStart) {
            assertTrue(o.success, "同流水号并发都应成功回放，实际: " + o.message);
            assertEquals("REPAIRING", o.data.get("status").asText());
        }
        JsonNode startAttempts = attempts(project2, "START");
        assertEquals(1, startAttempts.size(), "同一流水号只能形成一条生效尝试");
        assertEquals("EFFECTIVE", startAttempts.get(0).get("decision").asText());
    }

    // ------------------------------------------------------------------

    /** 建档 -> 立项 -> 会签通过（一级：外部->馆内->保管），项目停在 PLANNED */
    private long approvedProject(String projectNo, String relicName) throws Exception {
        long relicId = dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"R-" + projectNo + "\",\"name\":\"" + relicName
                + "\",\"category\":\"青铜器\",\"material\":\"青铜\",\"era\":\"商代\","
                + "\"source\":\"考古移交\",\"preservationGrade\":\"ONE\"}")).get("id").asLong();
        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"" + projectNo + "\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"清洗矫形\"}")).get("id").asLong();
        expectOk(postJson("/api/repair-projects/" + projectId + "/plan-versions",
                "{\"planContent\":\"方案V1\",\"internalExpert\":\"馆内专家甲\","
                        + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                        + "\"submittedBy\":\"负责人\"}"));
        JsonNode versions = dataOf(getJson(
                "/api/repair-projects/" + projectId + "/plan-versions"));
        long versionId = versions.get(0).get("version").get("id").asLong();
        sign(versionId, "EXTERNAL", "外部专家乙");
        sign(versionId, "INTERNAL", "馆内专家甲");
        sign(versionId, "CUSTODIAN", "保管员丙");
        return projectId;
    }

    private void sign(long versionId, String role, String name) throws Exception {
        expectOk(postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"" + role + "\",\"signerName\":\"" + name
                        + "\",\"decision\":\"APPROVE\"}"));
    }

    private String stageBody(String requestNo, String operator) {
        return "{\"stageName\":\"清洗\",\"stageResult\":\"已完成清洗\","
                + "\"operator\":\"" + operator + "\",\"requestNo\":\"" + requestNo + "\"}";
    }

    private String simpleBody(String requestNo, String operator) {
        return "{\"requestNo\":\"" + requestNo + "\",\"operatorName\":\"" + operator + "\"}";
    }

    private MvcResult start(long projectId, String requestNo, String operator) throws Exception {
        return postUri("/api/repair-projects/" + projectId + "/start",
                simpleBody(requestNo, operator));
    }

    private JsonNode attempts(long projectId, String operation) throws Exception {
        return dataOf(getJson("/api/repair-projects/" + projectId
                + "/operation-attempts?operation=" + operation));
    }

    private JsonNode projectDetail(long projectId) throws Exception {
        return dataOf(getJson("/api/repair-projects/" + projectId));
    }

    /** 阶段登记并发：POST /stages */
    private List<Outcome> parallelStages(long projectId, int threads,
                                         Function<Integer, String> bodyBuilder) throws Exception {
        return parallelPost(threads,
                "/api/repair-projects/" + projectId + "/stages", bodyBuilder);
    }

    /** 修复完成并发：POST /finish */
    private List<Outcome> parallelFinish(long projectId, int threads,
                                         Function<Integer, String> bodyBuilder) throws Exception {
        return parallelPost(threads,
                "/api/repair-projects/" + projectId + "/finish", bodyBuilder);
    }

    /** 通用并发 POST：所有线程同一 URI，各自生成 body，同时放行 */
    private List<Outcome> parallelPost(int threads, String uri,
                                       Function<Integer, String> bodyBuilder) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return toOutcome(mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyBuilder.apply(idx)))
                        .andExpect(status().isOk()).andReturn());
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private Outcome toOutcome(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        Outcome o = new Outcome();
        o.success = root.get("success").asBoolean();
        o.message = root.get("message").asText();
        o.data = root.get("data");
        return o;
    }

    private Outcome call(MvcResult result) throws Exception {
        return toOutcome(result);
    }

    private MvcResult postUri(String uri, String body) throws Exception {
        return postJson(uri, body);
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }
}
