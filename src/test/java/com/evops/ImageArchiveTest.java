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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物多角度影像归档规则验收：
 * 四视角批次、逐张校验（分辨率/拍摄时间/摄影者）、同文件去重、跨修复阶段新版本、
 * 作废不删文件、岗位脱敏下载、权限收紧上传停止但版本保留。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:image-archive;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImageArchiveTest {

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

    /** 上传一张照片的 multipart 规格（files/viewTypes/shotAts/photographers 同序） */
    private static class Spec {
        byte[] data;
        String name;
        String view;
        String shot;
        String photographer;

        Spec(byte[] data, String name, String view, String shot, String photographer) {
            this.data = data;
            this.name = name;
            this.view = view;
            this.shot = shot;
            this.photographer = photographer;
        }
    }

    @Test
    void image_archive_rules_hold() throws Exception {
        long relicId = createRelic("IMG-001");
        String pastShot = LocalDateTime.now().minusDays(3)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        byte[] front = png(1000, 1000, Color.RED);
        byte[] back = png(1000, 1000, Color.GREEN);
        byte[] bottom = png(1000, 1000, Color.BLUE);
        byte[] detail = png(1000, 1000, Color.YELLOW);

        // ---- 批次1：正面/背面/底部/细部按拍摄批次保存，V1，全部成功 ----
        JsonNode b1 = upload("/api/relics/" + relicId + "/image-batches", list(
                new Spec(front, "front.png", "FRONT", pastShot, "摄影甲"),
                new Spec(back, "back.png", "BACK", pastShot, "摄影甲"),
                new Spec(bottom, "bottom.png", "BOTTOM", pastShot, "摄影乙"),
                new Spec(detail, "detail.png", "DETAIL", pastShot, "摄影乙")));
        assertTrue(b1.get("success").asBoolean());
        JsonNode d1 = b1.get("data");
        assertEquals(4, d1.get("successCount").asInt());
        assertEquals(0, d1.get("failedCount").asInt());
        assertEquals(1, d1.get("versionNo").asInt());
        assertTrue(d1.get("batchNo").asText().startsWith("PB"));
        long frontFileId = d1.get("items").get(0).get("fileId").asLong();
        long frontImageId = d1.get("items").get(0).get("imageId").asLong();
        assertFalse(d1.get("items").get(0).get("duplicateFile").asBoolean());
        for (JsonNode item : d1.get("items")) {
            assertTrue(item.get("success").asBoolean());
            assertTrue(item.get("imageId").asLong() > 0);
        }

        // ---- 批次2：逐张校验，每张失败给出具体原因 ----
        JsonNode b2 = upload("/api/relics/" + relicId + "/image-batches", list(
                new Spec(png(100, 100, Color.GRAY), "tiny.png", "FRONT", pastShot, "摄影甲"),
                new Spec(png(1000, 1000, Color.CYAN), "badshot.png", "BACK", "bad-date", "摄影甲"),
                new Spec(png(1000, 1000, new Color(11, 22, 33)), "nophoto.png", "BOTTOM", pastShot, ""),
                new Spec(png(1000, 1000, new Color(44, 55, 66)), "side.png", "SIDE", pastShot, "摄影甲"),
                new Spec(png(1000, 1000, new Color(77, 88, 99)), "ok.png", "DETAIL", pastShot, "摄影丙")));
        JsonNode d2 = b2.get("data");
        assertEquals(1, d2.get("successCount").asInt());
        assertEquals(4, d2.get("failedCount").asInt());
        assertEquals(2, d2.get("versionNo").asInt(),
                "无修复阶段的新批次形成新版本 V2");
        assertReason(d2.get("items").get(0), "分辨率");
        assertReason(d2.get("items").get(1), "拍摄时间");
        assertReason(d2.get("items").get(2), "摄影者");
        assertReason(d2.get("items").get(3), "视角");
        assertTrue(d2.get("items").get(4).get("success").asBoolean());

        // ---- 同文件重复上传：只新增引用，物理文件仍只一份 ----
        JsonNode b3 = upload("/api/relics/" + relicId + "/image-batches", list(
                new Spec(front, "front-copy.png", "DETAIL", pastShot, "摄影甲")));
        JsonNode d3 = b3.get("data");
        assertEquals(3, d3.get("versionNo").asInt());
        assertTrue(d3.get("items").get(0).get("duplicateFile").asBoolean(),
                "同一文件重复上传应标记 duplicateFile");
        assertEquals(frontFileId, d3.get("items").get(0).get("fileId").asLong(),
                "复用同一物理文件记录");
        assertNotEquals(frontImageId, d3.get("items").get(0).get("imageId").asLong(),
                "但形成一条新的影像引用");
        // 内容寻址磁盘上该 SHA-256 文件只有一份
        long onDisk = Files.walk(tempDir)
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".png"))
                .count();
        assertEquals(5L, onDisk,
                "物理文件去重：批次1四张 + 批次2成功一张 + 批次3复用零新增 = 5 份");

        // ---- 不同修复阶段必须形成新版本；同一阶段补拍沿用版本 ----
        long projectId = createProject(relicId);
        countersign(projectId);
        postEmpty("/api/repair-projects/" + projectId + "/start");
        long stage1Id = createStage(projectId, "清洗", "表面清理");
        long stage2Id = createStage(projectId, "补全", "残缺补全");

        JsonNode s1 = upload("/api/relics/" + relicId + "/image-batches?projectId=" + projectId
                + "&stageId=" + stage1Id, list(
                new Spec(png(1000, 1000, Color.PINK), "s1a.png", "FRONT", pastShot, "摄影甲"),
                new Spec(png(1000, 1000, Color.ORANGE), "s1b.png", "BACK", pastShot, "摄影甲")));
        assertEquals(4, s1.get("data").get("versionNo").asInt(),
                "进入修复阶段1，形成新版本 V4");

        JsonNode s1More = upload("/api/relics/" + relicId + "/image-batches?projectId=" + projectId
                + "&stageId=" + stage1Id, list(
                new Spec(png(1000, 1000, new Color(101, 102, 103)), "s1c.png", "DETAIL", pastShot, "摄影甲")));
        assertEquals(4, s1More.get("data").get("versionNo").asInt(),
                "同一修复阶段补拍沿用 V4，不另起版本");

        JsonNode s2 = upload("/api/relics/" + relicId + "/image-batches?projectId=" + projectId
                + "&stageId=" + stage2Id, list(
                new Spec(png(1000, 1000, Color.MAGENTA), "s2a.png", "FRONT", pastShot, "摄影甲"),
                new Spec(png(1000, 1000, new Color(200, 201, 202)), "s2b.png", "BOTTOM", pastShot, "摄影甲")));
        assertEquals(5, s2.get("data").get("versionNo").asInt(),
                "不同修复阶段必须形成新版本 V5");
        long voidableImageId = s2.get("data").get("items").get(0).get("imageId").asLong();
        String voidableHashPrefix = hashPrefixOf(relicId, voidableImageId);

        // ---- 作废：置 VOID 留痕，原文件不删除 ----
        JsonNode voided = postJson("/api/images/" + voidableImageId + "/void",
                "{\"reason\":\"画面失焦，作废重拍\",\"operator\":\"组长\"}").get("data");
        assertEquals("VOID", voided.get("status").asText());
        // 原物理文件仍在磁盘
        assertTrue(Files.walk(tempDir).filter(Files::isRegularFile)
                .anyMatch(p -> p.getFileName().toString().startsWith(voidableHashPrefix)),
                "作废不删除原文件");
        // 引用行仍保留、状态为 VOID
        JsonNode versions = versions(relicId, "RESTORER");
        boolean foundVoid = false;
        for (JsonNode group : versions) {
            for (JsonNode img : group.get("images")) {
                if (img.get("imageId").asLong() == voidableImageId) {
                    foundVoid = true;
                    assertEquals("VOID", img.get("status").asText());
                }
            }
        }
        assertTrue(foundVoid, "作废影像仍在版本清单中可查");
        // 已作废不能下载、不能重复作废
        expectFailGet("/api/images/" + voidableImageId + "/download?role=RESTORER", "已作废");
        expectFailPost("/api/images/" + voidableImageId + "/void",
                "{\"reason\":\"再作废一次\"}", "已作废");

        // ---- 下载按岗位脱敏 ----
        // 修复师：原件，字节一致
        MvcResult restorer = mockMvc.perform(get("/api/images/" + frontImageId + "/download?role=RESTORER"))
                .andExpect(status().isOk()).andReturn();
        assertArrayEquals(front, restorer.getResponse().getContentAsByteArray());
        String cdRestorer = restorer.getResponse().getHeader("Content-Disposition");
        assertTrue(cdRestorer.contains("front.png") && !cdRestorer.contains("desensitized"));
        // 馆藏保管员：加水印脱敏 JPEG，非原件
        MvcResult curator = mockMvc.perform(get("/api/images/" + frontImageId + "/download?role=CURATOR"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(curator.getResponse().getContentType().startsWith("image/jpeg"));
        assertTrue(curator.getResponse().getHeader("Content-Disposition").contains("desensitized"));
        assertFalse(java.util.Arrays.equals(front, curator.getResponse().getContentAsByteArray()),
                "保管员拿到的是脱敏副本，不是原件");
        // 观摩访客：进一步缩放到长边 640
        MvcResult viewer = mockMvc.perform(get("/api/images/" + frontImageId + "/download?role=VIEWER"))
                .andExpect(status().isOk()).andReturn();
        BufferedImage viewerImg = ImageIO.read(new java.io.ByteArrayInputStream(
                viewer.getResponse().getContentAsByteArray()));
        assertEquals(640, viewerImg.getWidth());
        // 未声明岗位按最低权限脱敏
        MvcResult anon = mockMvc.perform(get("/api/images/" + frontImageId + "/download"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(anon.getResponse().getContentType().startsWith("image/jpeg"));
        // 非法岗位拒绝
        expectFailGet("/api/images/" + frontImageId + "/download?role=GUEST", "岗位");
        // 非修复师查看版本清单时摄影者脱敏
        JsonNode viewerVersions = versions(relicId, "VIEWER");
        boolean masked = false;
        for (JsonNode group : viewerVersions) {
            for (JsonNode img : group.get("images")) {
                if (img.get("imageId").asLong() == frontImageId) {
                    masked = img.get("photographer").asText().contains("**");
                }
            }
        }
        assertTrue(masked, "观摩岗位看不到完整摄影者姓名");

        // ---- 权限收紧：上传停止推进，已确认版本保留 ----
        int imageRowsBefore = countImageRows(versions);
        // 收紧
        JsonNode policy = putJson("/api/policy/repair-photo-upload",
                "{\"open\":false,\"operator\":\"馆办\",\"reason\":\"修复权限审计期间冻结\"}").get("data");
        assertFalse(policy.get("repairPhotoUploadOpen").asBoolean());
        // 上传被整体拒绝，不产生批次/版本
        expectFail(multipartResult("/api/relics/" + relicId + "/image-batches", list(
                new Spec(png(1000, 1000, Color.BLACK), "frozen.png", "FRONT", pastShot, "摄影甲"))),
                "收紧");
        JsonNode versionsFrozen = versions(relicId, "RESTORER");
        assertEquals(imageRowsBefore, countImageRows(versionsFrozen),
                "收紧期间上传不推进，影像版本不增加");
        // 已确认事实仍可查、可下载
        MvcResult downloadWhileClosed = mockMvc
                .perform(get("/api/images/" + frontImageId + "/download?role=RESTORER"))
                .andExpect(status().isOk()).andReturn();
        assertArrayEquals(front, downloadWhileClosed.getResponse().getContentAsByteArray(),
                "收紧不影响已确认版本的取用");
        // 放开后恢复上传，形成新版本 V6
        putJson("/api/policy/repair-photo-upload",
                "{\"open\":true,\"operator\":\"馆办\",\"reason\":\"审计结束\"}");
        JsonNode reopened = upload("/api/relics/" + relicId + "/image-batches", list(
                new Spec(png(1000, 1000, Color.WHITE), "reopened.png", "FRONT", pastShot, "摄影甲")));
        assertEquals(6, reopened.get("data").get("versionNo").asInt());

        // ---- 关键动作台账留痕（含收紧/放开，作废与上传） ----
        assertTrue(hasLedgerAction("IMAGE_UPLOAD"));
        assertTrue(hasLedgerAction("IMAGE_VOID"));
        assertTrue(hasLedgerAction("PERMIT_TIGHTEN"));
        assertTrue(hasLedgerAction("PERMIT_RELAX"));
    }

    // ------------------------------------------------------------------

    private long createRelic(String no) throws Exception {
        return postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"影像测试瓷瓶\",\"category\":\"瓷器\","
                + "\"material\":\"青白瓷\",\"era\":\"宋代\",\"source\":\"考古发掘\","
                + "\"preservationGrade\":\"ONE\"}").get("data").get("id").asLong();
    }

    private long createProject(long relicId) throws Exception {
        return postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-IMG-1\",\"relicId\":" + relicId
                + ",\"client\":\"文保中心\",\"plan\":\"清洗补全\"}").get("data").get("id").asLong();
    }

    private long createStage(long projectId, String name, String result) throws Exception {
        return postJson("/api/repair-projects/" + projectId + "/stages", "{"
                + "\"stageName\":\"" + name + "\",\"stageResult\":\"" + result
                + "\",\"operator\":\"修复师甲\"}").get("data").get("id").asLong();
    }

    /** 一级文物会签顺序：外部专家 -> 馆内专家 -> 保管人员，全部同意方可开工 */
    private void countersign(long projectId) throws Exception {
        long versionId = postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"清洗补全\",\"internalExpert\":\"馆内专家甲\","
                + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                + "\"submittedBy\":\"项目负责人\"}").get("data").get("version").get("id").asLong();
        postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"EXTERNAL\",\"signerName\":\"外部专家乙\",\"decision\":\"APPROVE\"}");
        postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"INTERNAL\",\"signerName\":\"馆内专家甲\",\"decision\":\"APPROVE\"}");
        postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"CUSTODIAN\",\"signerName\":\"保管员丙\",\"decision\":\"APPROVE\"}");
    }

    private JsonNode upload(String uri, List<Spec> specs) throws Exception {
        return body(multipartResult(uri, specs));
    }

    private MvcResult multipartResult(String uri, List<Spec> specs) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart(uri);
        for (Spec spec : specs) {
            builder.file(new MockMultipartFile(
                    "files", spec.name, "image/png", spec.data));
        }
        for (Spec spec : specs) {
            builder.param("viewTypes", spec.view);
        }
        for (Spec spec : specs) {
            builder.param("shotAts", spec.shot == null ? "" : spec.shot);
        }
        for (Spec spec : specs) {
            builder.param("photographers", spec.photographer == null ? "" : spec.photographer);
        }
        return mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
    }

    private JsonNode versions(long relicId, String role) throws Exception {
        return getJson("/api/relics/" + relicId + "/image-versions?role=" + role).get("data");
    }

    private String hashPrefixOf(long relicId, long imageId) throws Exception {
        for (JsonNode group : versions(relicId, "RESTORER")) {
            for (JsonNode img : group.get("images")) {
                if (img.get("imageId").asLong() == imageId) {
                    return img.get("contentHashPrefix").asText();
                }
            }
        }
        throw new IllegalStateException("未找到影像 " + imageId);
    }

    private int countImageRows(JsonNode versionGroups) {
        int n = 0;
        for (JsonNode group : versionGroups) {
            n += group.get("images").size();
        }
        return n;
    }

    private boolean hasLedgerAction(String action) {
        List<LedgerEntry> entries = ledgerMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LedgerEntry>()
                        .eq(LedgerEntry::getAction, action));
        return !entries.isEmpty();
    }

    private void assertReason(JsonNode item, String contains) {
        assertFalse(item.get("success").asBoolean());
        assertTrue(item.get("reason").asText().contains(contains),
                "失败原因应包含「" + contains + "」，实际：" + item.get("reason").asText());
    }

    private byte[] png(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.drawString(w + "x" + h, 10, 20);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private List<Spec> list(Spec... specs) {
        List<Spec> l = new ArrayList<>();
        for (Spec s : specs) {
            l.add(s);
        }
        return l;
    }

    private JsonNode postJson(String uri, String body) throws Exception {
        return body(mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode putJson(String uri, String body) throws Exception {
        return body(mockMvc.perform(put(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private void postEmpty(String uri) throws Exception {
        mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private JsonNode getJson(String uri) throws Exception {
        return body(mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = body(result);
        assertFalse(root.get("success").asBoolean(),
                "期望失败，但成功了");
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际："
                        + root.get("message").asText());
    }

    private void expectFailGet(String uri, String messageContains) throws Exception {
        expectFail(mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn(),
                messageContains);
    }

    private void expectFailPost(String uri, String body, String messageContains) throws Exception {
        expectFail(mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn(), messageContains);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
