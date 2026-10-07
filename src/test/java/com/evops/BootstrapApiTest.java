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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「博物馆文物修复档案起步」核心验收：
 * 先提交一条全链路有效样本；重复请求不产生第二条结果。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-bootstrap;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BootstrapApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void bootstrap_twice_creates_exactly_one_full_chain_sample() throws Exception {
        // 未起步时 GET 为 null
        MvcResult peekBefore = mockMvc.perform(get("/api/bootstrap"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(objectMapper.readTree(peekBefore.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data").isNull());

        // 第一次起步：created=true
        JsonNode first = postBootstrap();
        assertTrue(first.get("success").asBoolean());
        JsonNode firstData = first.get("data");
        assertTrue(firstData.get("created").asBoolean());
        assertFullChain(firstData);
        long relicId = firstData.get("relicId").asLong();
        long projectId = firstData.get("projectId").asLong();
        // 修复材料核算样本：三类材料领用 3 行、1 条超阈值差异待复核、1 张退料单
        assertEquals(3, firstData.get("materialLineCount").asInt());
        assertEquals(1, firstData.get("openReviewCount").asInt());
        assertEquals("MU-2026-0001", firstData.get("usageNo").asText());

        // 第二次起步：created=false，主键/编号完全一致，不产生第二条结果
        JsonNode second = postBootstrap();
        JsonNode secondData = second.get("data");
        assertTrue(second.get("success").asBoolean());
        assertFalse(secondData.get("created").asBoolean());
        assertEquals(relicId, secondData.get("relicId").asLong());
        assertEquals(projectId, secondData.get("projectId").asLong());
        assertEquals(firstData.get("relicNo").asText(), secondData.get("relicNo").asText());
        assertEquals(firstData.get("projectNo").asText(), secondData.get("projectNo").asText());
        assertFullChain(secondData);

        // 档案只有一条，台账链路动作完整且有序
        JsonNode dossier = getDossier(relicId);
        JsonNode relic = dossier.get("relic");
        assertEquals("ACTIVE", relic.get("status").asText());
        assertEquals("青铜", relic.get("material").asText());
        assertEquals("商代晚期", relic.get("era").asText());
        assertEquals("考古发掘移交", relic.get("source").asText());
        assertEquals("ONE", relic.get("preservationGrade").asText());

        assertEquals(1, dossier.get("intake").get("photos").size());
        assertEquals("一号库·铜器区·A-03 柜",
                dossier.get("intake").get("intake").get("location").asText());

        assertEquals(1, dossier.get("loans").size());
        assertEquals("RETURNED", dossier.get("loans").get(0).get("status").asText());

        assertEquals(1, dossier.get("repairProjects").size());
        assertEquals("ACCEPTED", dossier.get("repairProjects").get(0).get("status").asText());

        List<String> actions = new ArrayList<String>();
        dossier.get("ledger").forEach(e -> actions.add(e.get("action").asText()));
        assertEquals(Arrays.asList(
                "INTAKE", "LOAN_OUT", "RETURN",
                "PLAN_SUBMIT", "PLAN_SIGN", "PLAN_SIGN", "PLAN_APPROVE",
                "REPAIR_START",
                "MATERIAL_ISSUE", "MATERIAL_REVIEW", "MATERIAL_RETURN",
                "REPAIR_STAGE", "REPAIR_STAGE",
                "REPAIR_FINISH", "ACCEPT"), actions);
    }

    private void assertFullChain(JsonNode data) {
        assertEquals("WW-2026-0001", data.get("relicNo").asText());
        assertEquals("RU-2026-0001", data.get("intakeNo").asText());
        assertEquals("LO-2026-0001", data.get("loanNo").asText());
        assertEquals("RP-2026-0001", data.get("projectNo").asText());
        assertEquals(2, data.get("stageCount").asInt());
        assertTrue(data.get("acceptanceId").asLong() > 0);
        assertTrue(data.get("intakeId").asLong() > 0);
        assertTrue(data.get("loanId").asLong() > 0);
    }

    private JsonNode postBootstrap() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private JsonNode getDossier(long relicId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/relics/" + relicId))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }
}
