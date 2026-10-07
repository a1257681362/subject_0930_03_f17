package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 修复成果验收清单配置（museum.acceptance.*）。
 * 验收清单按"材质 + 工艺"生成：提交成果报告时以文物材质 + 报告工艺匹配模板，
 * 先精确匹配，再按 材质 + "*" 回落，最后落到 "*" + "*" 的默认模板。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.acceptance")
public class MuseumAcceptanceProperties {

    /** 清单模板列表：material/craft 支持 "*" 通配；items 为检查项内容 */
    private List<Template> checklist = new ArrayList<>();

    {
        Template def = new Template();
        def.setMaterial("*");
        def.setCraft("*");
        def.setItems(Arrays.asList(
                "修复部位与方案记录一致",
                "修复材料批号与用量已核销",
                "影像档案完整（修复前后对照）"));
        checklist.add(def);
    }

    @Data
    public static class Template {
        /** 材质，"*" 表示任意材质 */
        private String material;
        /** 工艺，"*" 表示任意工艺 */
        private String craft;
        /** 检查项内容列表 */
        private List<String> items = new ArrayList<>();
    }

    private static final List<String> FALLBACK = Arrays.asList(
            "修复部位与方案记录一致",
            "整体稳定性复核通过");

    /** 某材质 + 工艺的验收检查项；精确 -> 材质通配 -> 全通配 逐级回落 */
    public List<String> itemsFor(String material, String craft) {
        String mat = material == null ? "" : material.trim();
        String cft = craft == null ? "" : craft.trim();
        List<String> items = find(mat, cft);
        if (items == null) {
            items = find(mat, "*");
        }
        if (items == null) {
            items = find("*", "*");
        }
        return (items == null || items.isEmpty()) ? FALLBACK : items;
    }

    private List<String> find(String material, String craft) {
        for (Template t : checklist) {
            if (t.getItems() == null || t.getItems().isEmpty()) {
                continue;
            }
            if (material.equals(t.getMaterial()) && craft.equals(t.getCraft())) {
                return t.getItems();
            }
        }
        return null;
    }
}
