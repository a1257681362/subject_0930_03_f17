package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 修复方案会签配置（museum.countersign.*）。
 * 会签顺序按文物风险（保存等级 ONE/TWO/THREE）配置，
 * 值为馆内专家 INTERNAL / 外部专家 EXTERNAL / 保管人员 CUSTODIAN 的签署先后。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.countersign")
public class MuseumCountersignProperties {

    /** 按保存等级配置的会签顺序；未配置的等级回落到 DEFAULT_ORDER */
    private Map<String, List<String>> signOrder = new HashMap<>();

    {
        signOrder.put("ONE", Arrays.asList("EXTERNAL", "INTERNAL", "CUSTODIAN"));
        signOrder.put("TWO", Arrays.asList("INTERNAL", "EXTERNAL", "CUSTODIAN"));
        signOrder.put("THREE", Arrays.asList("INTERNAL", "CUSTODIAN", "EXTERNAL"));
    }

    private static final List<String> DEFAULT_ORDER =
            Arrays.asList("INTERNAL", "EXTERNAL", "CUSTODIAN");

    /** 某风险等级的会签顺序；等级未配置时按默认顺序 */
    public List<String> orderFor(String riskLevel) {
        List<String> order = signOrder.get(riskLevel);
        return (order == null || order.isEmpty()) ? DEFAULT_ORDER : order;
    }
}
