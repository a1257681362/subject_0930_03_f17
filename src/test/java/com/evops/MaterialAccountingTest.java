package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.entity.Material;
import com.evops.mapper.MaterialMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复材料批号与用量核算（museum-3）验收：
 * 三类材料按批号入库；过期批号禁领；工单领用记录用量/部位/操作者；
 * 超阈值差异触发且只触发一次复核（重启重扫不重复写记录、不丢操作者）；
 * 退料关联原领用单；批号/材料变更不改历史工单；库存与修复进度始终一致。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-material;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaterialAccountingTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MaterialMapper materialMapper;

    @Test
    void material_batch_and_usage_accounting_rules_hold() throws Exception {
        // ---- 建档 + 入藏 + 立项 ----
        long relicId = createRelic("{"
                + "\"relicNo\":\"M-001\",\"name\":\"测试青铜簋\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"西周\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"TWO\"}");
        postOk("/api/relics/" + relicId + "/intake", "{"
                + "\"intakeNo\":\"MI-001\",\"location\":\"三号库·铜器区·C-01\","
                + "\"photos\":[{\"photoUrl\":\"photos/m001/a.jpg\"}]}");
        long projectId = dataOf(postOk("/api/repair-projects", "{"
                + "\"projectNo\":\"MP-001\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"裂隙补配、随色封护\"}")).get("id").asLong();

        // 未开工不能领用材料（库存与修复进度一致：不允许脱离修复进度领料）
        expectFail(postJson("/api/material-usages", issueJson("MU-X", projectId,
                "[{\"batchNo\":\"B-GOOD\",\"planQty\":1,\"actualQty\":1,"
                        + "\"applyPart\":\"x\",\"operator\":\"修复师甲\"}]")),
                "不能领用材料");

        // 方案会签完成后才允许开工（museum-4：会签是生成施工任务的前置闸门）
        countersign(projectId);
        postEmpty("/api/repair-projects/" + projectId + "/start");

        // ---- 三类材料目录 ----
        long pigmentId = createMaterial("{"
                + "\"materialNo\":\"MM-P1\",\"name\":\"矿物颜料石青\",\"category\":\"PIGMENT\",\"unit\":\"g\"}");
        createMaterial("{\"materialNo\":\"MM-A1\",\"name\":\"B72 黏合剂\",\"category\":\"ADHESIVE\",\"unit\":\"ml\"}");
        createMaterial("{\"materialNo\":\"MM-R1\",\"name\":\"丙烯酸保护剂\",\"category\":\"PROTECTANT\",\"unit\":\"ml\"}");
        // 类别非法
        expectFail(postJson("/api/materials", "{"
                + "\"materialNo\":\"MM-X\",\"name\":\"杂项\",\"category\":\"OTHER\",\"unit\":\"g\"}"),
                "材料类别");

        // ---- 按批号入库 ----
        inbound("MM-P1", "B-GOOD", "100", "2027-12-31");
        // 同批号重复入库：库存与累计入库量都累加为 120
        inbound("MM-P1", "B-GOOD", "20", "2027-12-31");
        inbound("MM-A1", "B-FRESH", "100", "2027-06-30");
        // 已过期批号：允许入库登记，但不能领用
        inbound("MM-A1", "B-OLD", "50", "2026-09-01");
        inbound("MM-R1", "B-TINY", "5", "2027-12-31");

        assertEquals(120.0, stockOf(pigmentId, "B-GOOD"), 1e-3);
        // usable=true 过滤掉过期批号
        JsonNode usable = dataOf(getJson("/api/materials/batches?usable=true"));
        boolean oldUsable = false;
        for (JsonNode b : usable) {
            if ("B-OLD".equals(b.get("batchNo").asText())) {
                oldUsable = true;
            }
        }
        assertFalse(oldUsable, "过期批号不应出现在可领用列表");

        // ---- 工单领用：用量、部位、操作者 ----
        // 行1：计划10实际13，偏差30%超阈值；行2：计划20实际21，偏差5%不超阈值
        long usageId = dataOf(postJson("/api/material-usages", issueJson("MU-001", projectId,
                "[{\"batchNo\":\"B-GOOD\",\"planQty\":10,\"actualQty\":13,"
                        + "\"applyPart\":\"口沿补配\",\"operator\":\"修复师甲\"},"
                + "{\"batchNo\":\"B-FRESH\",\"planQty\":20,\"actualQty\":21,"
                        + "\"applyPart\":\"腹部裂隙\",\"operator\":\"修复师乙\"}]")))
                .get("id").asLong();

        // 库存按实际用量扣减且对账一致
        assertEquals(107.0, stockOf(pigmentId, "B-GOOD"), 1e-3);
        JsonNode reconcile = dataOf(getJson("/api/materials/batches/"
                + batchIdOf(pigmentId, "B-GOOD") + "/reconcile"));
        assertTrue(reconcile.get("consistent").asBoolean());
        assertEquals(120.0, reconcile.get("inboundTotal").asDouble(), 1e-3);
        assertEquals(13.0, reconcile.get("issuedTotal").asDouble(), 1e-3);

        JsonNode usageDetail = dataOf(getJson("/api/material-usages/" + usageId));
        JsonNode lines = usageDetail.get("lines");
        assertEquals(2, lines.size());
        long line1Id = lines.get(0).get("id").asLong();
        long line2Id = lines.get(1).get("id").asLong();
        assertEquals("PENDING", lines.get(0).get("reviewStatus").asText());
        assertEquals("NONE", lines.get(1).get("reviewStatus").asText());
        assertEquals(30.0, lines.get(0).get("variancePct").asDouble(), 1e-4);
        assertEquals("ISSUED", usageDetail.get("usage").get("status").asText());

        // ---- 过期批号禁领；库存不足禁领；失败整单回滚不扣库存 ----
        double stockBefore = stockOf(pigmentId, "B-GOOD");
        expectFail(postJson("/api/material-usages", issueJson("MU-EXPIRED", projectId,
                "[{\"batchNo\":\"B-GOOD\",\"planQty\":1,\"actualQty\":2,"
                        + "\"applyPart\":\"试验\",\"operator\":\"修复师甲\"},"
                + "{\"batchNo\":\"B-OLD\",\"planQty\":1,\"actualQty\":1,"
                        + "\"applyPart\":\"试验\",\"operator\":\"修复师甲\"}]")),
                "过期材料不能领用");
        assertEquals(stockBefore, stockOf(pigmentId, "B-GOOD"), "过期失败的整单必须回滚，不能先扣库存");

        expectFail(postJson("/api/material-usages", issueJson("MU-SHORT", projectId,
                "[{\"batchNo\":\"B-TINY\",\"planQty\":1,\"actualQty\":999,"
                        + "\"applyPart\":\"试验\",\"operator\":\"修复师甲\"}]")),
                "库存不足");

        // ---- 定时任务重启后补扫：只登记一次复核，不多写记录、不丢操作者 ----
        JsonNode scan1 = dataOf(postEmpty("/api/material-reviews/scan"));
        assertEquals(1, scan1.get("created").asInt(), "首轮扫描应补登记 1 条复核");
        JsonNode scan2 = dataOf(postEmpty("/api/material-reviews/scan"));
        assertEquals(0, scan2.get("created").asInt(), "重启后重扫不得重复登记");
        // 再调两轮，模拟定时任务反复重启
        postEmpty("/api/material-reviews/scan");
        postEmpty("/api/material-reviews/scan");

        JsonNode reviews = dataOf(getJson("/api/material-reviews"));
        assertEquals(1, reviews.size(), "同一差异行全生命周期只能有一条复核记录");
        JsonNode review = reviews.get(0);
        assertEquals("OPEN", review.get("status").asText());
        assertEquals("修复师甲", review.get("operator").asText(), "复核记录必须保留原操作者");
        assertEquals("口沿补配", review.get("applyPart").asText());
        assertEquals(30.0, review.get("variancePct").asDouble(), 1e-4);
        assertEquals(20.0, review.get("thresholdPct").asDouble(), 1e-4);

        // 台账同样只写一条差异动作
        JsonNode ledger = dataOf(getJson("/api/relics/" + relicId + "/ledger"));
        int reviewActions = 0;
        for (JsonNode e : ledger) {
            if ("MATERIAL_REVIEW".equals(e.get("action").asText())) {
                reviewActions++;
                assertTrue(e.get("summary").asText().contains("修复师甲"), "差异台账不得丢操作者");
            }
        }
        assertEquals(1, reviewActions, "重扫不得多写台账");

        // ---- 退料必须关联原领用单 ----
        expectFail(postJson("/api/material-returns", "{"
                + "\"returnNo\":\"MR-X\",\"usageId\":999999,\"lineId\":" + line1Id
                + ",\"returnQty\":1,\"returnedBy\":\"修复师甲\"}"), "原领用单不存在");
        // 累计退料不能超过实际用量
        expectFail(postJson("/api/material-returns", "{"
                + "\"returnNo\":\"MR-BIG\",\"usageId\":" + usageId + ",\"lineId\":" + line1Id
                + ",\"returnQty\":99,\"returnedBy\":\"修复师甲\"}"), "累计退料不能超过");

        // 正常退料 3g：库存加回原批号，部分退料不改变领用单状态
        postOk("/api/material-returns", "{"
                + "\"returnNo\":\"MR-001\",\"usageId\":" + usageId + ",\"lineId\":" + line1Id
                + ",\"returnQty\":3,\"reason\":\"随色余量退回\",\"returnedBy\":\"修复师甲\"}");
        assertEquals(110.0, stockOf(pigmentId, "B-GOOD"), 1e-3);
        assertEquals("ISSUED", dataOf(getJson("/api/material-usages/" + usageId))
                .get("usage").get("status").asText());
        JsonNode ret = dataOf(getJson("/api/material-usages/" + usageId)).get("returns").get(0);
        assertEquals("MR-001", ret.get("returnNo").asText());
        assertEquals(usageId, ret.get("usageId").asLong(), "退料必须关联原领用单");

        // ---- 材料/批号变更不影响历史工单 ----
        Material renamed = materialMapper.selectById(pigmentId);
        renamed.setName("材料已更名-不影响历史");
        materialMapper.updateById(renamed);
        JsonNode historyLines = dataOf(getJson("/api/material-usages/" + usageId)).get("lines");
        assertEquals("矿物颜料石青", historyLines.get(0).get("materialName").asText(),
                "材料目录更名后，历史工单仍保留领用当时的名称快照");
        assertEquals("B-GOOD", historyLines.get(0).get("batchNo").asText(),
                "批号后续变更不影响历史工单记录的批号");

        // ---- 复核确认：闭环但不覆盖操作者 ----
        long reviewId = review.get("id").asLong();
        postOk("/api/material-reviews/" + reviewId + "/confirm", "{"
                + "\"confirmedBy\":\"复核组钱七\",\"note\":\"实际随色面积大于方案，情况属实\"}");
        JsonNode confirmed = dataOf(getJson("/api/material-reviews")).get(0);
        assertEquals("CONFIRMED", confirmed.get("status").asText());
        assertEquals("修复师甲", confirmed.get("operator").asText(), "复核确认不得覆盖原操作者");
        assertEquals("复核组钱七", confirmed.get("confirmedBy").asText());
        JsonNode afterDetail = dataOf(getJson("/api/material-usages/" + usageId));
        assertEquals("CONFIRMED", afterDetail.get("usage").get("reviewStatus").asText());
        assertEquals("CONFIRMED", afterDetail.get("lines").get(0).get("reviewStatus").asText());
        assertEquals("修复师甲",
                afterDetail.get("lines").get(0).get("operator").asText(), "明细行操作者不可被改写");
        // 不能重复确认
        expectFail(postJson("/api/material-reviews/" + reviewId + "/confirm",
                "{\"confirmedBy\":\"复核组钱七\"}"), "不能重复确认");

        // ---- 全部退齐后整单 RETURNED，库存与流水始终对平 ----
        postOk("/api/material-returns", "{"
                + "\"returnNo\":\"MR-002\",\"usageId\":" + usageId + ",\"lineId\":" + line1Id
                + ",\"returnQty\":10,\"returnedBy\":\"修复师甲\"}");
        postOk("/api/material-returns", "{"
                + "\"returnNo\":\"MR-003\",\"usageId\":" + usageId + ",\"lineId\":" + line2Id
                + ",\"returnQty\":21,\"returnedBy\":\"修复师乙\"}");
        JsonNode finalDetail = dataOf(getJson("/api/material-usages/" + usageId));
        assertEquals("RETURNED", finalDetail.get("usage").get("status").asText());
        JsonNode finalReco = dataOf(getJson("/api/materials/batches/"
                + batchIdOf(pigmentId, "B-GOOD") + "/reconcile"));
        assertTrue(finalReco.get("consistent").asBoolean(), "退料加回后库存仍须与流水一致");
        assertEquals(120.0, finalReco.get("stockQty").asDouble(), 1e-3);
        assertEquals(13.0, finalReco.get("returnedTotal").asDouble(), 1e-3);
    }

    // ------------------------------------------------------------------

    private long createRelic(String body) throws Exception {
        return dataOf(postJson("/api/relics", body)).get("id").asLong();
    }

    /** 二级文物会签顺序：馆内专家 -> 外部专家 -> 保管人员，全部同意方可开工 */
    private void countersign(long projectId) throws Exception {
        long versionId = dataOf(postOk("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"裂隙补配、随色封护\",\"internalExpert\":\"馆内专家甲\","
                + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                + "\"submittedBy\":\"项目负责人\"}")).get("version").get("id").asLong();
        postOk("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"INTERNAL\",\"signerName\":\"馆内专家甲\",\"decision\":\"APPROVE\"}");
        postOk("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"EXTERNAL\",\"signerName\":\"外部专家乙\",\"decision\":\"APPROVE\"}");
        postOk("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"CUSTODIAN\",\"signerName\":\"保管员丙\",\"decision\":\"APPROVE\"}");
    }

    private long createMaterial(String body) throws Exception {
        return dataOf(postJson("/api/materials", body)).get("id").asLong();
    }

    private void inbound(String materialNo, String batchNo, String qty, String expire) throws Exception {
        postOk("/api/materials/inbound", "{"
                + "\"materialNo\":\"" + materialNo + "\",\"batchNo\":\"" + batchNo + "\""
                + ",\"qty\":" + qty + ",\"expireDate\":\"" + expire + "\""
                + ",\"supplier\":\"修复材料中心\",\"inboundBy\":\"库管员\"}");
    }

    private String issueJson(String usageNo, long projectId, String linesJson) {
        return "{\"usageNo\":\"" + usageNo + "\",\"projectId\":" + projectId
                + ",\"lines\":" + linesJson + "}";
    }

    private double stockOf(long materialId, String batchNo) throws Exception {
        for (JsonNode b : dataOf(getJson("/api/materials/batches?materialId=" + materialId))) {
            if (batchNo.equals(b.get("batchNo").asText())) {
                return b.get("stockQty").asDouble();
            }
        }
        throw new IllegalStateException("批号未找到: " + batchNo);
    }

    private long batchIdOf(long materialId, String batchNo) throws Exception {
        for (JsonNode b : dataOf(getJson("/api/materials/batches?materialId=" + materialId))) {
            if (batchNo.equals(b.get("batchNo").asText())) {
                return b.get("id").asLong();
            }
        }
        throw new IllegalStateException("批号未找到: " + batchNo);
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postOk(String uri, String body) throws Exception {
        MvcResult result = postJson(uri, body);
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
        return result;
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
