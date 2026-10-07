package com.evops.dto;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

/**
 * 年度报表五项指标的统计口径规格（museum-16）。
 *
 * <p>整体随口径版本序列化为 {@code caliberJson} 固化；报表核对永远读取其
 * 绑定版本的这份规格，口径发布新版本不影响已出报表的核对结果。</p>
 * <ul>
 *   <li>{@code project} 立项数：归属时间字段 + 计入项目状态；</li>
 *   <li>{@code completed} 完成数：归属时间字段（通常为 finish_date）+ 计入状态；</li>
 *   <li>{@code material} 材料消耗：归属时间字段（通常为领用时间），是否扣减退料；</li>
 *   <li>{@code loan} 借展次数：归属时间字段（通常为协议版本签署时间）；</li>
 *   <li>{@code anomaly} 异常事件：计入的异常类型（环境异常/设备故障）；</li>
 *   <li>{@code requireAcceptanceDocs}：完成项目是否必须有 PASSED 成果报告，
 *       缺少即标记待补（PENDING_SUPPLEMENT）。</li>
 * </ul>
 */
@Data
public class AnnualCaliberSpec {

    private MetricCaliber project;
    private MetricCaliber completed;
    private MaterialCaliber material;
    private LoanCaliber loan;
    private AnomalyCaliber anomaly;
    private boolean requireAcceptanceDocs = true;

    /** 默认口径（口径版本 V1） */
    public static AnnualCaliberSpec defaults() {
        AnnualCaliberSpec spec = new AnnualCaliberSpec();
        spec.setProject(new MetricCaliber("CREATE_TIME",
                Arrays.asList("PLANNED", "REPAIRING", "REPAIRED", "ACCEPTED")));
        spec.setCompleted(new MetricCaliber("FINISH_DATE",
                Arrays.asList("REPAIRED", "ACCEPTED")));
        spec.setMaterial(new MaterialCaliber("USAGE_AT", true));
        spec.setLoan(new LoanCaliber("SIGNED_AT"));
        spec.setAnomaly(new AnomalyCaliber(Arrays.asList("ENV_EVENT", "EQUIPMENT_FAULT"),
                "FIRST_AT", "FAULT_AT"));
        spec.setRequireAcceptanceDocs(true);
        return spec;
    }

    /** 单项指标口径：归属时间字段 + 计入状态集合 */
    @Data
    public static class MetricCaliber {
        /** 允许的归属时间列（白名单，防止任意列名拼接） */
        private String dateField;
        private List<String> statuses;

        public MetricCaliber() {
        }

        public MetricCaliber(String dateField, List<String> statuses) {
            this.dateField = dateField;
            this.statuses = statuses;
        }
    }

    /** 材料消耗口径 */
    @Data
    public static class MaterialCaliber {
        private String dateField;
        /** 净消耗是否扣减累计退料 */
        private boolean deductReturn;

        public MaterialCaliber() {
        }

        public MaterialCaliber(String dateField, boolean deductReturn) {
            this.dateField = dateField;
            this.deductReturn = deductReturn;
        }
    }

    /** 借展次数口径：以借展协议版本签署时间归属年度 */
    @Data
    public static class LoanCaliber {
        private String dateField;

        public LoanCaliber() {
        }

        public LoanCaliber(String dateField) {
            this.dateField = dateField;
        }
    }

    /** 异常事件口径：计入类型 + 各类异常归属时间字段 */
    @Data
    public static class AnomalyCaliber {
        /** ENV_EVENT / EQUIPMENT_FAULT */
        private List<String> types;
        private String envDateField;
        private String faultDateField;

        public AnomalyCaliber() {
        }

        public AnomalyCaliber(List<String> types, String envDateField, String faultDateField) {
            this.types = types;
            this.envDateField = envDateField;
            this.faultDateField = faultDateField;
        }
    }
}
