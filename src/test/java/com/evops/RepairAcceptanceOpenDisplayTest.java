package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.entity.LedgerEntry;
import com.evops.mapper.LedgerEntryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复成果验收与开放标记（museum-14）验收：
 * 验收清单按材质+工艺生成；修复师提交成果报告后馆内专家与保管员分别逐项确认，
 * 缺一项即整改、整改后重新提交新一轮；开放展示标记只能在验收通过后设置；
 * 开放后发现问题开立补修单且原开放记录保留；公众页面只展示已授权影像与摘要；
 * 详情、列表、CSV 导出、台账流水四处口径一致可交叉核对。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:accept-open-display;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairAcceptanceOpenDisplayTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void imageProps(DynamicPropertyRegistry registry) {
        registry.add("museum.image.storage-root", () -> tempDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LedgerEntryMapper ledgerMapper;

    @Test
    void acceptance_and_open_display_rules_hold() throws Exception {
        // ---- 项目一：青铜器（材质+工艺精确模板）走完全链路 ----
        long relicId = createRelic("AC-001", "青铜鼎", "青铜");
        long projectId = createProject(relicId, "AP-001");
        countersignAndRepair(projectId);

        // 未修复完成不能提交成果报告
        long relicWip = createRelic("AC-900", "陶罐", "陶");
        long wipProject = createProject(relicWip, "AP-900");
        expectFailPost("/api/repair-projects/" + wipProject + "/accept-reports",
                reportJson("修补", "摘要", "专家甲", "保管员乙", "修复师丙"), "未修复完成");

        // ---- 提交第 1 轮报告：清单按 青铜|清洗 模板生成 ----
        JsonNode r1 = dataOf(postJson("/api/repair-projects/" + projectId + "/accept-reports",
                reportJson("清洗", "清洗缓蚀完成", "专家甲", "保管员乙", "修复师丙")));
        long report1 = r1.get("report").get("id").asLong();
        assertEquals(1, r1.get("report").get("roundNo").asInt());
        assertEquals("IN_REVIEW", r1.get("report").get("status").asText());
        JsonNode items1 = r1.get("items");
        assertEquals(4, items1.size(), "青铜|清洗 模板应生成 4 项清单");
        assertEquals("表面无清洗剂残留", items1.get(0).get("content").asText());
        assertEquals("影像档案完整（修复前后对照）", items1.get(3).get("content").asText());

        // 确认中不能重复提交
        expectFailPost("/api/repair-projects/" + projectId + "/accept-reports",
                reportJson("清洗", "重复提交", "专家甲", "保管员乙", "修复师丙"), "确认中");

        // ---- 确认规则：角色非法 / 代签 / 未覆盖全部清单项 都被拒绝 ----
        expectFailPost("/api/accept-reports/" + report1 + "/confirm",
                confirmJson("EXTERNAL", "专家甲", items1, "OK"), "角色");
        expectFailPost("/api/accept-reports/" + report1 + "/confirm",
                confirmJson("INTERNAL", "管理员", items1, "OK"), "代签");
        expectFailPost("/api/accept-reports/" + report1 + "/confirm",
                "{\"role\":\"INTERNAL\",\"confirmer\":\"专家甲\",\"items\":["
                        + "{\"itemId\":" + items1.get(0).get("id").asLong() + ",\"result\":\"OK\"}]}",
                "覆盖全部");

        // 馆内专家确认：第 2 项缺项 -> 立即整改
        JsonNode afterExpert = dataOf(postJson("/api/accept-reports/" + report1 + "/confirm",
                confirmJsonWithMissing("INTERNAL", "专家甲", items1, 1)));
        assertEquals("RECTIFICATION", afterExpert.get("report").get("status").asText());
        assertEquals("OK", afterExpert.get("items").get(0).get("expertResult").asText());
        assertEquals("MISSING", afterExpert.get("items").get(1).get("expertResult").asText());
        assertTrue(afterExpert.get("items").get(1).get("custodianResult").isNull());

        // 整改中的报告只读：保管员不能再确认，也不能重复确认
        expectFailPost("/api/accept-reports/" + report1 + "/confirm",
                confirmJson("CUSTODIAN", "保管员乙", items1, "OK"), "整改");

        // ---- 第 2 轮报告：双方全部合格 -> 验收通过 ----
        JsonNode r2 = dataOf(postJson("/api/repair-projects/" + projectId + "/accept-reports",
                reportJson("清洗", "补拍影像后重新提交", "专家甲", "保管员乙", "修复师丙")));
        long report2 = r2.get("report").get("id").asLong();
        assertEquals(2, r2.get("report").get("roundNo").asInt());
        JsonNode items2 = r2.get("items");
        assertEquals(4, items2.size());

        dataOf(postJson("/api/accept-reports/" + report2 + "/confirm",
                confirmJson("INTERNAL", "专家甲", items2, "OK")));
        // 同一角色不能重复确认
        expectFailPost("/api/accept-reports/" + report2 + "/confirm",
                confirmJson("INTERNAL", "专家甲", items2, "OK"), "重复确认");
        JsonNode passed = dataOf(postJson("/api/accept-reports/" + report2 + "/confirm",
                confirmJson("CUSTODIAN", "保管员乙", items2, "OK")));
        assertEquals("PASSED", passed.get("report").get("status").asText());

        // 验收通过后不再接受新报告
        expectFailPost("/api/repair-projects/" + projectId + "/accept-reports",
                reportJson("清洗", "第三轮", "专家甲", "保管员乙", "修复师丙"), "已通过");

        // ---- 项目二：书画（材质|* 回落模板）完成修复但不验收，用于开放标记门槛 ----
        long relic2 = createRelic("AC-002", "山水画", "书画");
        long project2 = createProject(relic2, "AP-002");
        countersignAndRepair(project2);
        JsonNode r3 = dataOf(postJson("/api/repair-projects/" + project2 + "/accept-reports",
                reportJson("全色", "全色完成", "专家丁", "保管员戊", "修复师己")));
        JsonNode items3 = r3.get("items");
        assertEquals(3, items3.size(), "书画|* 回落模板应生成 3 项清单");
        assertEquals("画心平整无新增折痕", items3.get(0).get("content").asText());

        // ---- 开放展示标记：验收未通过不能设置 ----
        long img1 = uploadImage(relicId, "front.png", "FRONT");
        long img2 = uploadImage(relicId, "back.png", "BACK");
        long imgOther = uploadImage(relic2, "other.png", "FRONT");

        expectFailPost("/api/repair-projects/" + project2 + "/open-display",
                openJson("书画修复成果", img1), "验收未通过");
        // 授权影像不属于该文物
        expectFailPost("/api/repair-projects/" + projectId + "/open-display",
                openJson("青铜鼎修复成果", imgOther), "不属于该文物");

        JsonNode grant = dataOf(postJson("/api/repair-projects/" + projectId + "/open-display",
                "{\"summary\":\"青铜鼎清洗修复成果展\",\"imageIds\":[" + img1 + "," + img2 + "],"
                        + "\"grantedBy\":\"保管员乙\"}"));
        long grantId = grant.get("id").asLong();
        assertEquals("ACTIVE", grant.get("status").asText());
        assertEquals(report2, grant.get("reportId").asLong());
        // 不能重复设置
        expectFailPost("/api/repair-projects/" + projectId + "/open-display",
                openJson("重复设置", img1), "已存在生效中");

        // ---- 公众页面：只展示已授权影像与对外摘要，不暴露内部字段 ----
        JsonNode publicList = dataOf(getJson("/api/public/open-displays"));
        assertEquals(1, publicList.size());
        JsonNode pub = publicList.get(0);
        assertEquals("青铜鼎清洗修复成果展", pub.get("summary").asText());
        assertEquals("青铜鼎", pub.get("name").asText());
        assertEquals(2, pub.get("images").size());
        assertFalse(pub.get("images").get(0).has("photographer"), "公众视图不得暴露摄影者");
        assertFalse(pub.has("grantedBy"), "公众视图不得暴露设置人");
        JsonNode pubDetail = dataOf(getJson("/api/public/open-displays/" + relicId));
        assertEquals("青铜鼎清洗修复成果展", pubDetail.get("summary").asText());
        // 未开放的文物公众不可见
        expectFailGet("/api/public/open-displays/" + relic2, "未开放");

        // ---- 补修单：开放后发现问题，原开放记录保留 ----
        expectFailPost("/api/repair-projects/" + project2 + "/supplements",
                "{\"issue\":\"展签脱落\",\"createdBy\":\"保管员戊\"}", "开放展示标记");
        JsonNode supp = dataOf(postJson("/api/repair-projects/" + projectId + "/supplements",
                "{\"issue\":\"局部封护层变色\",\"createdBy\":\"保管员乙\"}"));
        long suppId = supp.get("id").asLong();
        assertEquals("OPEN", supp.get("status").asText());
        assertTrue(supp.get("orderNo").asText().startsWith("SP-"));
        assertEquals(grantId, supp.get("grantId").asLong());
        // 原开放记录保留：管理端仍 ACTIVE，公众页面仍可见
        JsonNode grants = dataOf(getJson("/api/repair-projects/" + projectId + "/open-displays"));
        assertEquals(1, grants.size());
        assertEquals("ACTIVE", grants.get(0).get("grant").get("status").asText());
        assertEquals(1, dataOf(getJson("/api/public/open-displays")).size());

        JsonNode closed = dataOf(postJson("/api/supplements/" + suppId + "/close",
                "{\"closedBy\":\"修复师丙\",\"closeNote\":\"已局部补封护并复检合格\"}"));
        assertEquals("CLOSED", closed.get("status").asText());
        expectFailPost("/api/supplements/" + suppId + "/close",
                "{\"closedBy\":\"修复师丙\",\"closeNote\":\"重复闭环\"}", "已闭环");
        JsonNode supps = dataOf(getJson("/api/repair-projects/" + projectId + "/supplements"));
        assertEquals(1, supps.size());
        assertEquals("CLOSED", supps.get(0).get("status").asText());

        // ---- 交叉核对一：详情 <-> 列表 <-> CSV 导出行集一致 ----
        JsonNode list = dataOf(getJson("/api/accept-reports?relicId=" + relicId));
        assertEquals(2, list.size(), "项目一两轮报告都应出现在列表");
        assertEquals("RECTIFICATION", list.get(0).get("status").asText());
        assertEquals("PASSED", list.get(1).get("status").asText());
        assertEquals(1, list.get(0).get("missingCount").asInt());
        assertEquals(4, list.get(1).get("itemCount").asInt());

        JsonNode passedOnly = dataOf(getJson("/api/accept-reports?status=PASSED"));
        assertEquals(1, passedOnly.size());
        assertEquals(report2, passedOnly.get(0).get("reportId").asLong());

        String csv = mockMvc.perform(get("/api/accept-reports/export?relicId=" + relicId))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String[] csvLines = csv.trim().split("\n");
        assertEquals(1 + list.size(), csvLines.length, "CSV 行数应为表头 + 列表行数");
        assertTrue(csvLines[1].contains("AP-001") && csvLines[1].contains("RECTIFICATION"));
        assertTrue(csvLines[2].contains("AP-001") && csvLines[2].contains("PASSED"));
        assertTrue(csvLines[2].contains(",4,0,"), "第二轮 4 项清单 0 缺项，与详情一致");
        // 详情与列表口径一致
        JsonNode detail2 = dataOf(getJson("/api/accept-reports/" + report2));
        assertEquals(list.get(1).get("status").asText(),
                detail2.get("report").get("status").asText());
        assertEquals(list.get(1).get("itemCount").asInt(), detail2.get("items").size());

        // ---- 交叉核对二：台账流水动作序列完整可溯 ----
        List<String> actions = ledgerMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LedgerEntry>()
                                .eq(LedgerEntry::getRelicId, relicId)
                                .orderByAsc(LedgerEntry::getOccurredAt)
                                .orderByAsc(LedgerEntry::getId))
                .stream().map(LedgerEntry::getAction).collect(Collectors.toList());
        assertActionCount(actions, "ACCEPT_REPORT_SUBMIT", 2);
        assertActionCount(actions, "ACCEPT_CONFIRM", 3);
        assertActionCount(actions, "ACCEPT_RECTIFY", 1);
        assertActionCount(actions, "ACCEPT_PASS", 1);
        assertActionCount(actions, "OPEN_DISPLAY_SET", 1);
        assertActionCount(actions, "SUPPLEMENT_OPEN", 1);
        assertActionCount(actions, "SUPPLEMENT_CLOSE", 1);
        // 关键顺序：整改在第 1 轮确认后、通过在第 2 轮确认后、开放设置在通过之后
        assertTrue(actions.indexOf("ACCEPT_RECTIFY") < actions.indexOf("ACCEPT_PASS"));
        assertTrue(actions.indexOf("ACCEPT_PASS") < actions.indexOf("OPEN_DISPLAY_SET"));
        assertTrue(actions.indexOf("OPEN_DISPLAY_SET") < actions.indexOf("SUPPLEMENT_OPEN"));

        // ---- 撤销开放标记：留痕保留，公众不可见，补修单记录仍在 ----
        dataOf(postJson("/api/open-displays/" + grantId + "/revoke",
                "{\"reason\":\"展厅改陈\",\"operator\":\"保管员乙\"}"));
        assertEquals(0, dataOf(getJson("/api/public/open-displays")).size());
        expectFailGet("/api/public/open-displays/" + relicId, "未开放");
        JsonNode grantsAfter = dataOf(getJson("/api/repair-projects/" + projectId + "/open-displays"));
        assertEquals("REVOKED", grantsAfter.get(0).get("grant").get("status").asText());
        assertEquals(1, dataOf(getJson("/api/repair-projects/" + projectId + "/supplements")).size(),
                "撤销开放标记不影响既有补修单记录");
        // 撤销后不能再开立补修单
        expectFailPost("/api/repair-projects/" + projectId + "/supplements",
                "{\"issue\":\"再次发现问题\",\"createdBy\":\"保管员乙\"}", "开放展示标记");
        List<String> actionsAfter = ledgerMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LedgerEntry>()
                                .eq(LedgerEntry::getRelicId, relicId))
                .stream().map(LedgerEntry::getAction).collect(Collectors.toList());
        assertTrue(actionsAfter.contains("OPEN_DISPLAY_REVOKE"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void assertActionCount(List<String> actions, String action, int expected) {
        long count = actions.stream().filter(action::equals).count();
        assertEquals(expected, count, "台账动作 " + action + " 应出现 " + expected + " 次");
    }

    private long createRelic(String no, String name, String material) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + name + "\",\"category\":\"青铜器\","
                + "\"material\":\"" + material + "\",\"era\":\"商代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"ONE\"}")).get("id").asLong();
    }

    private long createProject(long relicId, String projectNo) throws Exception {
        return dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"" + projectNo + "\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"清洗、保护\"}")).get("id").asLong();
    }

    /** 一级文物会签（外部->馆内->保管）-> 开工 -> 阶段 -> 完成，进入待验收 */
    private void countersignAndRepair(long projectId) throws Exception {
        long versionId = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"清洗保护\",\"internalExpert\":\"馆内专家甲\","
                + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                + "\"submittedBy\":\"项目负责人\"}")).get("version").get("id").asLong();
        postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"EXTERNAL\",\"signerName\":\"外部专家乙\",\"decision\":\"APPROVE\"}");
        postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"INTERNAL\",\"signerName\":\"馆内专家甲\",\"decision\":\"APPROVE\"}");
        postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"CUSTODIAN\",\"signerName\":\"保管员丙\",\"decision\":\"APPROVE\"}");
        postJson("/api/repair-projects/" + projectId + "/start", "{}");
        postJson("/api/repair-projects/" + projectId + "/stages",
                "{\"stageName\":\"清洗\",\"stageResult\":\"表面清洗完成\",\"operator\":\"修复师丙\"}");
        postJson("/api/repair-projects/" + projectId + "/finish", "{}");
    }

    private String reportJson(String craft, String summary, String expert,
                              String custodian, String submitter) {
        return "{\"craft\":\"" + craft + "\",\"summary\":\"" + summary
                + "\",\"expertName\":\"" + expert + "\",\"custodianName\":\"" + custodian
                + "\",\"submittedBy\":\"" + submitter + "\"}";
    }

    /** 全部清单项同一结果的确认报文 */
    private String confirmJson(String role, String confirmer, JsonNode items, String result) {
        StringBuilder sb = new StringBuilder("{\"role\":\"" + role + "\",\"confirmer\":\""
                + confirmer + "\",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"itemId\":").append(items.get(i).get("id").asLong())
                    .append(",\"result\":\"").append(result).append("\"}");
        }
        return sb.append("]}").toString();
    }

    /** 指定序号项 MISSING、其余 OK 的确认报文 */
    private String confirmJsonWithMissing(String role, String confirmer, JsonNode items,
                                          int missingIndex) {
        StringBuilder sb = new StringBuilder("{\"role\":\"" + role + "\",\"confirmer\":\""
                + confirmer + "\",\"opinion\":\"第" + (missingIndex + 1) + "项不达标\",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"itemId\":").append(items.get(i).get("id").asLong())
                    .append(",\"result\":\"").append(i == missingIndex ? "MISSING" : "OK")
                    .append("\"}");
        }
        return sb.append("]}").toString();
    }

    private String openJson(String summary, long imageId) {
        return "{\"summary\":\"" + summary + "\",\"imageIds\":[" + imageId
                + "],\"grantedBy\":\"保管员乙\"}";
    }

    /** 上传一张影像并返回影像 id（1000x1000 满足归档分辨率下限） */
    private long uploadImage(long relicId, String name, String view) throws Exception {
        String shot = LocalDateTime.now().minusDays(1)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        MockMultipartHttpServletRequestBuilder builder =
                multipart("/api/relics/" + relicId + "/image-batches");
        builder.file(new MockMultipartFile("files", name, "image/png",
                png(1000, 1000, Color.BLUE)));
        builder.param("viewTypes", view);
        builder.param("shotAts", shot);
        builder.param("photographers", "摄影甲");
        JsonNode resp = body(mockMvc.perform(builder).andExpect(status().isOk()).andReturn());
        assertTrue(resp.get("success").asBoolean());
        return resp.get("data").get("items").get(0).get("imageId").asLong();
    }

    private byte[] png(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private JsonNode postJson(String uri, String body) throws Exception {
        return body(mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode getJson(String uri) throws Exception {
        return body(mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode dataOf(JsonNode root) {
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际：" + root.toString());
        return root.get("data");
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return dataOf(body(result));
    }

    private void expectFailPost(String uri, String body, String messageContains) throws Exception {
        JsonNode root = body(mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        assertFalse(root.get("success").asBoolean(), "期望失败，但成功了");
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际：" + root.get("message").asText());
    }

    private void expectFailGet(String uri, String messageContains) throws Exception {
        JsonNode root = body(mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn());
        assertFalse(root.get("success").asBoolean(), "期望失败，但成功了");
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际：" + root.get("message").asText());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
