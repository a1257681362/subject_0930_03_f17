package com.evops.constant;

/**
 * 文物修复台账各业务表的状态与动作常量。
 * 库中一律存字符串码值，禁止用物理删除表达"撤销"。
 */
public final class MuseumConstants {

    private MuseumConstants() {
    }

    /** 文物档案 / 入藏登记状态 */
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    /** 保存等级：一 / 二 / 三级 */
    public static final String GRADE_ONE = "ONE";
    public static final String GRADE_TWO = "TWO";
    public static final String GRADE_THREE = "THREE";

    /** 修复项目状态：立项 -> 修复中 -> 修复完成 -> 已验收（作废为旁路终态） */
    public static final String PROJECT_PLANNED = "PLANNED";
    public static final String PROJECT_REPAIRING = "REPAIRING";
    public static final String PROJECT_REPAIRED = "REPAIRED";
    public static final String PROJECT_ACCEPTED = "ACCEPTED";
    public static final String PROJECT_CANCELLED = "CANCELLED";

    /** 借还状态 */
    public static final String LOAN_LENT = "LENT";
    public static final String LOAN_RETURNED = "RETURNED";
    public static final String LOAN_OVERDUE = "OVERDUE";

    /** 验收结论 */
    public static final String ACCEPT_PASS = "PASS";
    public static final String ACCEPT_CONDITIONAL = "CONDITIONAL";

    /** 台账流水动作 */
    public static final String ACTION_INTAKE = "INTAKE";
    public static final String ACTION_LOAN_OUT = "LOAN_OUT";
    public static final String ACTION_RETURN = "RETURN";
    public static final String ACTION_REPAIR_START = "REPAIR_START";
    public static final String ACTION_REPAIR_STAGE = "REPAIR_STAGE";
    public static final String ACTION_REPAIR_FINISH = "REPAIR_FINISH";
    public static final String ACTION_ACCEPT = "ACCEPT";
    public static final String ACTION_REVOKE = "REVOKE";
    public static final String ACTION_CANCEL = "CANCEL";
    /** 影像归档：批量上传确认 / 影像作废 / 权限收紧 / 权限放开 */
    public static final String ACTION_IMAGE_UPLOAD = "IMAGE_UPLOAD";
    public static final String ACTION_IMAGE_VOID = "IMAGE_VOID";
    public static final String ACTION_POLICY_TIGHTEN = "PERMIT_TIGHTEN";
    public static final String ACTION_POLICY_RELAX = "PERMIT_RELAX";

    /** 起步幂等锁固定主键，全表只允许一行 */
    public static final int BOOTSTRAP_LOCK_ID = 1;

    // --------------------------------------------------------------------
    // museum-2 文物多角度影像归档
    // --------------------------------------------------------------------

    /** 影像视角：正面 / 背面 / 底部 / 细部 */
    public static final String VIEW_FRONT = "FRONT";
    public static final String VIEW_BACK = "BACK";
    public static final String VIEW_BOTTOM = "BOTTOM";
    public static final String VIEW_DETAIL = "DETAIL";

    /** 影像引用 / 拍摄批次状态：有效 / 已作废（作废不是删除） */
    public static final String IMAGE_ACTIVE = "ACTIVE";
    public static final String IMAGE_VOID = "VOID";
    public static final String BATCH_CONFIRMED = "CONFIRMED";

    /** 权限策略表固定主键，全表只允许一行 */
    public static final int POLICY_ID = 1;

    /** 岗位（下载脱敏按岗位区分）：修复师 / 馆藏保管员 / 观摩访客 */
    public static final String ROLE_RESTORER = "RESTORER";
    public static final String ROLE_CURATOR = "CURATOR";
    public static final String ROLE_VIEWER = "VIEWER";

    // --------------------------------------------------------------------
    // museum-3 修复材料批号与用量核算
    // --------------------------------------------------------------------

    /** 材料类别：颜料 / 黏合剂 / 保护剂 */
    public static final String MATERIAL_PIGMENT = "PIGMENT";
    public static final String MATERIAL_ADHESIVE = "ADHESIVE";
    public static final String MATERIAL_PROTECTANT = "PROTECTANT";

    /** 材料目录状态：在用 / 停用（停用不影响历史领用事实） */
    public static final String MATERIAL_ACTIVE = "ACTIVE";
    public static final String MATERIAL_DISABLED = "DISABLED";

    /** 批号库存状态：在库 / 用尽 / 报废 */
    public static final String BATCH_IN_STOCK = "IN_STOCK";
    public static final String BATCH_EXHAUSTED = "EXHAUSTED";
    public static final String BATCH_DISPOSED = "DISPOSED";

    /** 领用单状态：已领用 / 已退料 */
    public static final String USAGE_ISSUED = "ISSUED";
    public static final String USAGE_RETURNED = "RETURNED";

    /** 复核状态：无需复核 / 待复核 / 复核确认（明细行与领用单共用） */
    public static final String REVIEW_NONE = "NONE";
    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_CONFIRMED = "CONFIRMED";
    public static final String REVIEW_OPEN = "OPEN";

    /** 台账流水动作：材料入库 / 领用 / 退料 / 差异待复核 / 复核确认 */
    public static final String ACTION_MATERIAL_INBOUND = "MATERIAL_INBOUND";
    public static final String ACTION_MATERIAL_ISSUE = "MATERIAL_ISSUE";
    public static final String ACTION_MATERIAL_RETURN = "MATERIAL_RETURN";
    public static final String ACTION_MATERIAL_REVIEW = "MATERIAL_REVIEW";
    public static final String ACTION_MATERIAL_REVIEW_CONFIRM = "MATERIAL_REVIEW_CONFIRM";

    // --------------------------------------------------------------------
    // museum-4 修复方案会签
    // --------------------------------------------------------------------

    /** 方案版本状态：会签中 / 已退回（只读） / 会签通过 */
    public static final String PLAN_SIGNING = "SIGNING";
    public static final String PLAN_RETURNED = "RETURNED";
    public static final String PLAN_APPROVED = "APPROVED";

    /** 会签角色：馆内专家 / 外部专家 / 保管人员 */
    public static final String SIGN_ROLE_INTERNAL = "INTERNAL";
    public static final String SIGN_ROLE_EXTERNAL = "EXTERNAL";
    public static final String SIGN_ROLE_CUSTODIAN = "CUSTODIAN";

    /** 会签决定：同意 / 退回 */
    public static final String SIGN_DECISION_APPROVE = "APPROVE";
    public static final String SIGN_DECISION_RETURN = "RETURN";

    /** 台账流水动作：方案提交会签 / 会签签署 / 方案退回 / 会签通过 */
    public static final String ACTION_PLAN_SUBMIT = "PLAN_SUBMIT";
    public static final String ACTION_PLAN_SIGN = "PLAN_SIGN";
    public static final String ACTION_PLAN_RETURN = "PLAN_RETURN";
    public static final String ACTION_PLAN_APPROVE = "PLAN_APPROVE";

    // --------------------------------------------------------------------
    // museum-5 展厅温湿度与照度巡检
    // --------------------------------------------------------------------

    /** 展厅状态：开放 / 关闭（关闭后不再生成新巡检任务，历史保留） */
    public static final String HALL_ACTIVE = "ACTIVE";
    public static final String HALL_CLOSED = "CLOSED";

    /** 展厅文物陈展状态：展出中 / 已撤展 */
    public static final String DISPLAY_ON = "ON_DISPLAY";
    public static final String DISPLAY_WITHDRAWN = "WITHDRAWN";

    /** 环境规则版本状态：草稿 / 当前生效 / 已被新版本取代（只追加不改写） */
    public static final String ENV_RULE_DRAFT = "DRAFT";
    public static final String ENV_RULE_ACTIVE = "ACTIVE";
    public static final String ENV_RULE_SUPERSEDED = "SUPERSEDED";

    /** 巡检任务状态：待巡检 / 已完成 / 已取消（规则新版本不改写已完成巡检） */
    public static final String TASK_PENDING = "PENDING";
    public static final String TASK_DONE = "DONE";
    public static final String TASK_CANCELLED = "CANCELLED";

    /** 环境指标码：温度 / 相对湿度 / 照度 */
    public static final String METRIC_TEMP = "TEMP";
    public static final String METRIC_HUMIDITY = "HUMIDITY";
    public static final String METRIC_LUX = "LUX";

    /**
     * 异常事件状态：
     * OPEN 异常持续中（连续超限合并）/ HANDLING 处置中
     * / RESOLVED 指标恢复待保管员复核 / CLOSED 保管员复核关闭
     */
    public static final String EVENT_OPEN = "OPEN";
    public static final String EVENT_HANDLING = "HANDLING";
    public static final String EVENT_RESOLVED = "RESOLVED";
    public static final String EVENT_CLOSED = "CLOSED";

    /** 环境台账流水动作 */
    public static final String ACTION_RULE_PUBLISH = "RULE_PUBLISH";
    public static final String ACTION_TASK_GENERATE = "TASK_GENERATE";
    public static final String ACTION_READING_OK = "READING_OK";
    public static final String ACTION_READING_ABNORMAL = "READING_ABNORMAL";
    public static final String ACTION_EVENT_OPEN = "EVENT_OPEN";
    public static final String ACTION_EVENT_MERGE = "EVENT_MERGE";
    public static final String ACTION_EVENT_RESOLVE = "EVENT_RESOLVE";
    public static final String ACTION_EVENT_HANDLE = "EVENT_HANDLE";
    public static final String ACTION_EVENT_REVIEW_REJECT = "EVENT_REVIEW_REJECT";
    public static final String ACTION_EVENT_CLOSE = "EVENT_CLOSE";

    // --------------------------------------------------------------------
    // museum-6 文物借展协议与押运闭环
    // --------------------------------------------------------------------

    /** 借展协议主单状态：待签 / 已签生效 / 押运中 / 已归还验收 / 已取消 */
    public static final String AGR_DRAFT = "DRAFT";
    public static final String AGR_SIGNED = "SIGNED";
    public static final String AGR_IN_TRANSIT = "IN_TRANSIT";
    public static final String AGR_RETURNED = "RETURNED";
    public static final String AGR_CANCELLED = "CANCELLED";

    /** 协议版本状态：待签 / 已签生效 / 已被新版本取代（只追加不改写） */
    public static final String AGR_VERSION_DRAFT = "DRAFT";
    public static final String AGR_VERSION_SIGNED = "SIGNED";
    public static final String AGR_VERSION_SUPERSEDED = "SUPERSEDED";

    /** 押运节点：封箱 -> 交接 -> 到馆 -> 开箱（顺序固定） */
    public static final String NODE_SEAL = "SEAL";
    public static final String NODE_HANDOVER = "HANDOVER";
    public static final String NODE_ARRIVAL = "ARRIVAL";
    public static final String NODE_OPEN_BOX = "OPEN_BOX";

    /** 归还验收结论：全部相符 / 存在差异 */
    public static final String CHECK_ALL_MATCH = "ALL_MATCH";
    public static final String CHECK_WITH_DIFF = "WITH_DIFF";

    /** 归还逐行比对结果：相符 / 损伤 / 缺失 */
    public static final String LINE_INTACT = "INTACT";
    public static final String LINE_DAMAGED = "DAMAGED";
    public static final String LINE_MISSING = "MISSING";

    /** 台账流水动作：协议登记/签署/变更、节点登记/异常/异常解除、归还验收 */
    public static final String ACTION_LOAN_AGR_REGISTER = "LOAN_AGR_REGISTER";
    public static final String ACTION_LOAN_AGR_SIGN = "LOAN_AGR_SIGN";
    public static final String ACTION_LOAN_AGR_AMEND = "LOAN_AGR_AMEND";
    public static final String ACTION_ESCORT_NODE = "ESCORT_NODE";
    public static final String ACTION_ESCORT_ANOMALY = "ESCORT_ANOMALY";
    public static final String ACTION_ESCORT_RESUME = "ESCORT_RESUME";
    public static final String ACTION_LOAN_RETURN_CHECK = "LOAN_RETURN_CHECK";

    // --------------------------------------------------------------------
    // museum-7 文物库房库位与密集架预约
    // --------------------------------------------------------------------

    /** 温区：恒温 / 冷藏 / 常温 */
    public static final String ZONE_CONST_TEMP = "CONST_TEMP";
    public static final String ZONE_COLD = "COLD";
    public static final String ZONE_ORDINARY = "ORDINARY";

    /** 防火等级：F1 最高 / F2 / F3 */
    public static final String FIRE_F1 = "F1";
    public static final String FIRE_F2 = "F2";
    public static final String FIRE_F3 = "F3";

    /** 库区 / 密集架状态：在用 / 关闭停用 */
    public static final String STORAGE_ACTIVE = "ACTIVE";
    public static final String STORAGE_CLOSED = "CLOSED";

    /** 格位状态：可用 / 已停用 */
    public static final String CELL_ACTIVE = "ACTIVE";
    public static final String CELL_DISABLED = "DISABLED";

    /** 预约（兼移动主单）状态：已预约 / 已开工 / 已完成 / 已取消 / 超时释放 */
    public static final String RES_RESERVED = "RESERVED";
    public static final String RES_MOVING = "MOVING";
    public static final String RES_COMPLETED = "COMPLETED";
    public static final String RES_CANCELLED = "CANCELLED";
    public static final String RES_EXPIRED = "EXPIRED";

    /** 移动单状态：移动中 / 已完成 / 已取消 */
    public static final String MOVE_MOVING = "MOVING";
    public static final String MOVE_COMPLETED = "COMPLETED";
    public static final String MOVE_CANCELLED = "CANCELLED";

    /** 库存查询岗位：库房管理员（见全部）/ 馆藏保管员（敏感位置脱敏）/ 访客（过滤敏感位置） */
    public static final String ROLE_WAREHOUSE_KEEPER = "WAREHOUSE_KEEPER";

    /** 库房台账流水动作 */
    public static final String ACTION_CELL_MAINTAIN = "CELL_MAINTAIN";
    public static final String ACTION_RESERVE = "RESERVE";
    public static final String ACTION_RESERVE_CANCEL = "RESERVE_CANCEL";
    public static final String ACTION_RESERVE_EXPIRE = "RESERVE_EXPIRE";
    public static final String ACTION_MOVE_START = "MOVE_START";
    public static final String ACTION_MOVE_COMPLETE = "MOVE_COMPLETE";

    // --------------------------------------------------------------------
    // museum-8 虫害监测
    // --------------------------------------------------------------------

    /** 虫害阈值规则版本状态：草稿 / 当前生效 / 已被新版本取代（只追加不改写） */
    public static final String PEST_RULE_DRAFT = "DRAFT";
    public static final String PEST_RULE_ACTIVE = "ACTIVE";
    public static final String PEST_RULE_SUPERSEDED = "SUPERSEDED";

    /** 诱捕点位区域类型：展厅 / 库房 */
    public static final String PEST_ZONE_HALL = "HALL";
    public static final String PEST_ZONE_STORAGE = "STORAGE";

    /** 诱捕点位状态：在用 / 停用（停用不删历史） */
    public static final String PEST_POINT_ACTIVE = "ACTIVE";
    public static final String PEST_POINT_RETIRED = "RETIRED";

    /** 诱捕片状态：在布 / 已更换（更换不删旧片与旧监测记录） */
    public static final String PEST_CARD_ACTIVE = "ACTIVE";
    public static final String PEST_CARD_REPLACED = "REPLACED";

    /** 监测结论：正常 / 超阈值（结论随记录固化，规则新版本不改写旧结论） */
    public static final String PEST_CONCLUSION_NORMAL = "NORMAL";
    public static final String PEST_CONCLUSION_OVER = "OVER_THRESHOLD";

    /** 监测提交人角色：馆内人员 / 外包人员（外包只能提交现场数据，不能关闭任务） */
    public static final String PEST_ROLE_INTERNAL = "INTERNAL";
    public static final String PEST_ROLE_OUTSOURCE = "OUTSOURCE";

    /** 处置任务状态：待处置 / 已关闭 */
    public static final String PEST_TASK_OPEN = "OPEN";
    public static final String PEST_TASK_CLOSED = "CLOSED";

    /** 虫害台账流水动作 */
    public static final String ACTION_PEST_RULE_PUBLISH = "RULE_PUBLISH";
    public static final String ACTION_PEST_POINT_CREATE = "POINT_CREATE";
    public static final String ACTION_PEST_POINT_MOVE = "POINT_MOVE";
    public static final String ACTION_PEST_POINT_RETIRE = "POINT_RETIRE";
    public static final String ACTION_PEST_CARD_DEPLOY = "CARD_DEPLOY";
    public static final String ACTION_PEST_CARD_REPLACE = "CARD_REPLACE";
    public static final String ACTION_PEST_RECORD_SUBMIT = "RECORD_SUBMIT";
    public static final String ACTION_PEST_TASK_GENERATE = "TASK_GENERATE";
    public static final String ACTION_PEST_TASK_CLOSE = "TASK_CLOSE";
    public static final String ACTION_PEST_MONTH_FREEZE = "MONTH_FREEZE";

    // --------------------------------------------------------------------
    // museum-9 修复工时与设备排程
    // --------------------------------------------------------------------

    /** 修复设备状态：在用 / 故障中（故障不删历史排程，恢复后回到在用） */
    public static final String EQUIP_ACTIVE = "ACTIVE";
    public static final String EQUIP_FAULT = "FAULT";

    /** 工时单版本状态：待审核 / 审核通过 / 已退回（退回封存只读，只能补充新版本） */
    public static final String TS_SUBMITTED = "SUBMITTED";
    public static final String TS_APPROVED = "APPROVED";
    public static final String TS_RETURNED = "RETURNED";

    /** 工时审核决定：通过 / 退回 */
    public static final String REVIEW_APPROVE = "APPROVE";
    public static final String REVIEW_RETURN = "RETURN";

    /** 设备故障单状态：故障中 / 已恢复 */
    public static final String FAULT_OPEN = "OPEN";
    public static final String FAULT_CLOSED = "CLOSED";

    /** 改期建议状态：待处理 / 已采纳 / 已忽略（采纳不改写原排程，仅记录处理意见） */
    public static final String SUGGEST_PENDING = "PENDING";
    public static final String SUGGEST_ADOPTED = "ADOPTED";
    public static final String SUGGEST_DISMISSED = "DISMISSED";

    /** 台账流水动作：工时提交 / 审核通过 / 审核退回 / 故障改期建议 */
    public static final String ACTION_TS_SUBMIT = "TS_SUBMIT";
    public static final String ACTION_TS_APPROVE = "TS_APPROVE";
    public static final String ACTION_TS_RETURN = "TS_RETURN";
    public static final String ACTION_RESCHEDULE_SUGGEST = "RESCHEDULE_SUGGEST";

    // --------------------------------------------------------------------
    // museum-10 文物借阅审批
    // --------------------------------------------------------------------

    /** 可借阅场景：馆外展览 / 学术交流 / 科研观摩 / 宣教活动 */
    public static final String BORROW_SCENE_EXHIBITION = "OUT_EXHIBITION";
    public static final String BORROW_SCENE_EXCHANGE = "ACADEMIC_EXCHANGE";
    public static final String BORROW_SCENE_RESEARCH = "RESEARCH";
    public static final String BORROW_SCENE_EDUCATION = "PUBLIC_EDUCATION";

    /** 借阅审批角色：馆藏部 / 馆藏主管 / 馆长（高风险追加签名） */
    public static final String BORROW_ROLE_DEPT = "BORROW_DEPT";
    public static final String BORROW_ROLE_LEADER = "COLLECTION_LEADER";
    public static final String BORROW_ROLE_DIRECTOR = "DIRECTOR";

    /**
     * 借阅申请状态：
     * PENDING 审批中 / APPROVED 已批准待出库 / BORROWED 已出库 / RETURNED 已归还
     * / REJECTED 已驳回 / CANCELLED 申请人撤销
     */
    public static final String BAPP_PENDING = "PENDING";
    public static final String BAPP_APPROVED = "APPROVED";
    public static final String BAPP_BORROWED = "BORROWED";
    public static final String BAPP_RETURNED = "RETURNED";
    public static final String BAPP_REJECTED = "REJECTED";
    public static final String BAPP_CANCELLED = "CANCELLED";

    /** 归还延期重新审批状态：PENDING 重新审批中 / APPROVED / REJECTED */
    public static final String EXT_PENDING = "PENDING";
    public static final String EXT_APPROVED = "APPROVED";
    public static final String EXT_REJECTED = "REJECTED";

    /** 借阅审批决定：同意 / 驳回 */
    public static final String BORROW_DECISION_APPROVE = "APPROVE";
    public static final String BORROW_DECISION_REJECT = "REJECT";

    /** 补偿记录状态：OPEN 待闭环 / RESOLVED 已闭环 */
    public static final String COMP_OPEN = "OPEN";
    public static final String COMP_RESOLVED = "RESOLVED";

    /** 借阅补偿处置方式：在借须提前收回 / 已归还须补充等级与保险差异确认 */
    public static final String BCOMP_ACTION_RECALL = "RECALL";
    public static final String BCOMP_ACTION_SUPPLEMENT = "SUPPLEMENT_CONFIRM";

    /** 台账流水动作：借阅申请/审批链/出库/归还/延期重审/撤销/策略变更 */
    public static final String ACTION_BORROW_APPLY = "BORROW_APPLY";
    public static final String ACTION_BORROW_APPROVE = "BORROW_APPROVE";
    public static final String ACTION_BORROW_REJECT = "BORROW_REJECT";
    public static final String ACTION_BORROW_LEND = "BORROW_LEND";
    public static final String ACTION_BORROW_RETURN = "BORROW_RETURN";
    public static final String ACTION_BORROW_EXTEND_SUBMIT = "BORROW_EXTEND_SUBMIT";
    public static final String ACTION_BORROW_EXTEND_APPROVE = "BORROW_EXTEND_APPROVE";
    public static final String ACTION_BORROW_EXTEND_REJECT = "BORROW_EXTEND_REJECT";
    public static final String ACTION_BORROW_CANCEL = "BORROW_CANCEL";
    public static final String ACTION_BORROW_POLICY_UPDATE = "BORROW_POLICY_UPDATE";

    /** 台账流水动作：部分成功等级补偿 / 借阅补偿 的开立与闭环 */
    public static final String ACTION_GRADE_COMP_OPEN = "GRADE_COMP_OPEN";
    public static final String ACTION_GRADE_COMP_RESOLVE = "GRADE_COMP_RESOLVE";
    public static final String ACTION_BORROW_COMP_OPEN = "BORROW_COMP_OPEN";
    public static final String ACTION_BORROW_COMP_RESOLVE = "BORROW_COMP_RESOLVE";

    // --------------------------------------------------------------------
    // museum-11 文物鉴定意见 与 修复操作并发审计
    // --------------------------------------------------------------------

    /** 鉴定案件状态：待初鉴 / 待复鉴 / 仲裁中 / 已定稿 */
    public static final String APPRAISAL_OPEN = "OPEN";
    public static final String APPRAISAL_REVIEWING = "REVIEWING";
    public static final String APPRAISAL_ARBITRATING = "ARBITRATING";
    public static final String APPRAISAL_CONCLUDED = "CONCLUDED";

    /** 鉴定轮次：初鉴 / 复鉴 */
    public static final String APPRAISAL_STAGE_INITIAL = "INITIAL";
    public static final String APPRAISAL_STAGE_REVIEW = "REVIEW";
    public static final String APPRAISAL_STAGE_ARBITRATION = "ARBITRATION";

    /** 鉴定结论码：真 / 伪 / 部分真 / 暂不定论 */
    public static final String CONCLUSION_AUTHENTIC = "AUTHENTIC";
    public static final String CONCLUSION_COUNTERFEIT = "COUNTERFEIT";
    public static final String CONCLUSION_PARTIAL = "PARTIAL";
    public static final String CONCLUSION_INCONCLUSIVE = "INCONCLUSIVE";

    /** 意见状态：未发布（可撤回）/ 已发布（只能追加更正）/ 已撤回（留痕） */
    public static final String OPINION_DRAFT = "DRAFT";
    public static final String OPINION_PUBLISHED = "PUBLISHED";
    public static final String OPINION_WITHDRAWN = "WITHDRAWN";

    /** 仲裁决定：采初鉴 / 采复鉴 / 综合调整 */
    public static final String ARB_ADOPT_INITIAL = "ADOPT_INITIAL";
    public static final String ARB_ADOPT_REVIEW = "ADOPT_REVIEW";
    public static final String ARB_ADJUSTED = "ADJUSTED";

    /** 仲裁单状态 */
    public static final String ARB_OPEN = "OPEN";
    public static final String ARB_CONCLUDED = "CONCLUDED";

    /** 报告下载审计结果：放行 / 拒绝（拒绝同样留痕） */
    public static final String REPORT_GRANTED = "GRANTED";
    public static final String REPORT_DENIED = "DENIED";

    /** 详细报告可见岗位：鉴定专家 / 仲裁人；普通馆员只见结论摘要 */
    public static final String ROLE_APPRAISAL_EXPERT = "APPRAISAL_EXPERT";
    public static final String ROLE_ARBITER = "ARBITER";
    public static final String ROLE_LIBRARIAN = "LIBRARIAN";

    /** 修复并发操作尝试决定：生效 / 被拒绝 */
    public static final String ATTEMPT_EFFECTIVE = "EFFECTIVE";
    public static final String ATTEMPT_REJECTED = "REJECTED";

    /** 修复并发操作类型 */
    public static final String OP_START = "START";
    public static final String OP_STAGE = "STAGE";
    public static final String OP_FINISH = "FINISH";
    public static final String OP_ACCEPT = "ACCEPT";
    public static final String OP_CANCEL = "CANCEL";

    /** 台账流水动作：鉴定各环节、报告下载、修复并发拒绝 */
    public static final String ACTION_APPRAISAL_OPEN = "APPRAISAL_OPEN";
    public static final String ACTION_APPRAISAL_DRAFT = "APPRAISAL_DRAFT";
    public static final String ACTION_APPRAISAL_PUBLISH = "APPRAISAL_PUBLISH";
    public static final String ACTION_APPRAISAL_WITHDRAW = "APPRAISAL_WITHDRAW";
    public static final String ACTION_APPRAISAL_CORRECT = "APPRAISAL_CORRECT";
    public static final String ACTION_APPRAISAL_ARB_OPEN = "APPRAISAL_ARB_OPEN";
    public static final String ACTION_APPRAISAL_ARB_CONCLUDE = "APPRAISAL_ARB_CONCLUDE";
    public static final String ACTION_APPRAISAL_CONCLUDE = "APPRAISAL_CONCLUDE";
    public static final String ACTION_APPRAISAL_REPORT_DOWNLOAD = "APPRAISAL_REPORT_DL";
    public static final String ACTION_APPRAISAL_REPORT_DENY = "APPRAISAL_REPORT_DENY";
    public static final String ACTION_REPAIR_OP_REJECT = "REPAIR_OP_REJECT";

    // --------------------------------------------------------------------
    // museum-12 展览借品清点盘点
    // --------------------------------------------------------------------

    /** 清点任务状态：清点中 / 已关闭（关闭后只能新增补充记录） */
    public static final String INV_TASK_OPEN = "OPEN";
    public static final String INV_TASK_CLOSED = "CLOSED";

    /** 扫描判定：相符 / 错位（箱号或点位不符）/ 状态异常（已撤销或在借却在场）/ 未在基线 */
    public static final String INV_SCAN_MATCHED = "MATCHED";
    public static final String INV_SCAN_MISPLACED = "MISPLACED";
    public static final String INV_SCAN_ABNORMAL = "ABNORMAL";
    public static final String INV_SCAN_UNEXPECTED = "UNEXPECTED";

    /** 差异类型：缺失 / 错位 / 状态异常 */
    public static final String INV_DIFF_MISSING = "MISSING";
    public static final String INV_DIFF_MISPLACED = "MISPLACED";
    public static final String INV_DIFF_ABNORMAL = "STATUS_ABNORMAL";

    /** 差异单状态：待复核 / 复核完成 */
    public static final String INV_DIFF_OPEN = "OPEN";
    public static final String INV_DIFF_REVIEWED = "REVIEWED";

    /** 清点台账流水动作 */
    public static final String ACTION_INV_TASK_GENERATE = "INV_TASK_GENERATE";
    public static final String ACTION_INV_SCAN = "INV_SCAN";
    public static final String ACTION_INV_REBASELINE = "INV_REBASELINE";
    public static final String ACTION_INV_DIFF_CREATE = "INV_DIFF_CREATE";
    public static final String ACTION_INV_DIFF_REVIEW = "INV_DIFF_REVIEW";
    public static final String ACTION_INV_DIFF_REVIEW_REJECT = "INV_DIFF_REVIEW_REJECT";
    public static final String ACTION_INV_TASK_CLOSE = "INV_TASK_CLOSE";
    public static final String ACTION_INV_SUPPLEMENT = "INV_SUPPLEMENT";
    public static final String ACTION_INV_HALL_CLOSE = "INV_HALL_CLOSE";

    // --------------------------------------------------------------------
    // museum-13 文物运输包装规格
    // --------------------------------------------------------------------

    /** 包装规格版本状态：草稿 / 当前生效 / 已被新版本取代（只追加不改写） */
    public static final String PACK_SPEC_DRAFT = "DRAFT";
    public static final String PACK_SPEC_ACTIVE = "ACTIVE";
    public static final String PACK_SPEC_SUPERSEDED = "SUPERSEDED";

    /** 缓冲（防震）等级：L1 特级（防震最强，适用冲击限值最严）/ L2 标准 / L3 普通 */
    public static final String CUSHION_L1 = "L1";
    public static final String CUSHION_L2 = "L2";
    public static final String CUSHION_L3 = "L3";

    /** 气候区：温带 / 湿热 / 严寒 / 干燥 */
    public static final String CLIMATE_TEMPERATE = "TEMPERATE";
    public static final String CLIMATE_HOT_HUMID = "HOT_HUMID";
    public static final String CLIMATE_COLD = "COLD";
    public static final String CLIMATE_DRY = "DRY";

    /** 装箱类型：按规格装箱 / 临时替代包装 */
    public static final String PACK_STANDARD = "STANDARD";
    public static final String PACK_SUBSTITUTE = "SUBSTITUTE";

    /**
     * 装箱单状态：
     * PENDING_APPROVAL 替代待审批 / NOTIFY_PENDING 待外部依赖确认
     * / PACKED 已装箱确认（可发运）/ UNPACKED 已拆箱 / REJECTED 替代被拒
     */
    public static final String PACK_ORDER_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String PACK_ORDER_NOTIFY_PENDING = "NOTIFY_PENDING";
    public static final String PACK_ORDER_PACKED = "PACKED";
    public static final String PACK_ORDER_UNPACKED = "UNPACKED";
    public static final String PACK_ORDER_REJECTED = "REJECTED";

    /** 替代包装审批状态 */
    public static final String SUB_PENDING = "PENDING";
    public static final String SUB_APPROVED = "APPROVED";
    public static final String SUB_REJECTED = "REJECTED";

    /** 装箱照片阶段：发运前装箱证据 / 拆箱后包装状态证据（两阶段分别只追加） */
    public static final String PACK_PHOTO_BEFORE_SHIP = "BEFORE_SHIP";
    public static final String PACK_PHOTO_AFTER_UNPACK = "AFTER_UNPACK";

    /** 拆箱包装状态：完好 / 受损 */
    public static final String UNPACK_INTACT = "INTACT";
    public static final String UNPACK_DAMAGED = "DAMAGED";

    /** 外部依赖通知状态：待投递 / 投递认领中 / 已送达（SENT 不重投） */
    public static final String NOTIFY_PENDING = "PENDING";
    public static final String NOTIFY_SENDING = "SENDING";
    public static final String NOTIFY_SENT = "SENT";

    /** 台账流水动作：规格发布/装箱/替代审批/外部通知/拆箱 */
    public static final String ACTION_PACK_SPEC_PUBLISH = "PACK_SPEC_PUBLISH";
    public static final String ACTION_PACK_STANDARD = "PACK_STANDARD";
    public static final String ACTION_PACK_SUBSTITUTE_APPLY = "PACK_SUBSTITUTE_APPLY";
    public static final String ACTION_PACK_SUBSTITUTE_APPROVE = "PACK_SUBSTITUTE_APPROVE";
    public static final String ACTION_PACK_SUBSTITUTE_REJECT = "PACK_SUBSTITUTE_REJECT";
    public static final String ACTION_PACK_NOTIFY_PENDING = "PACK_NOTIFY_PENDING";
    public static final String ACTION_PACK_NOTIFY_SENT = "PACK_NOTIFY_SENT";
    public static final String ACTION_PACK_UNPACK = "PACK_UNPACK";

    // --------------------------------------------------------------------
    // museum-14 修复成果验收与开放标记
    // --------------------------------------------------------------------

    /** 成果报告状态：确认中 / 整改中（缺项，待新一轮报告）/ 验收通过（终态） */
    public static final String AREPORT_IN_REVIEW = "IN_REVIEW";
    public static final String AREPORT_RECTIFICATION = "RECTIFICATION";
    public static final String AREPORT_PASSED = "PASSED";

    /** 清单项确认结果：合格 / 缺项（任一角色任一项缺项即整改） */
    public static final String ITEM_OK = "OK";
    public static final String ITEM_MISSING = "MISSING";

    /** 开放展示标记状态：生效中 / 已撤销（撤销留痕，记录保留） */
    public static final String DISPLAY_GRANT_ACTIVE = "ACTIVE";
    public static final String DISPLAY_GRANT_REVOKED = "REVOKED";

    /** 补修单状态：待补修 / 已闭环 */
    public static final String SUPP_OPEN = "OPEN";
    public static final String SUPP_CLOSED = "CLOSED";

    /** 台账流水动作：成果报告提交 / 逐项确认 / 整改 / 验收通过 / 开放标记设置与撤销 / 补修单开立与闭环 */
    public static final String ACTION_ACCEPT_REPORT_SUBMIT = "ACCEPT_REPORT_SUBMIT";
    public static final String ACTION_ACCEPT_CONFIRM = "ACCEPT_CONFIRM";
    public static final String ACTION_ACCEPT_RECTIFY = "ACCEPT_RECTIFY";
    public static final String ACTION_ACCEPT_PASS = "ACCEPT_PASS";
    public static final String ACTION_OPEN_DISPLAY_SET = "OPEN_DISPLAY_SET";
    public static final String ACTION_OPEN_DISPLAY_REVOKE = "OPEN_DISPLAY_REVOKE";
    public static final String ACTION_SUPPLEMENT_OPEN = "SUPPLEMENT_OPEN";
    public static final String ACTION_SUPPLEMENT_CLOSE = "SUPPLEMENT_CLOSE";

    // --------------------------------------------------------------------
    // museum-15 文物保险估值管理
    // --------------------------------------------------------------------

    /**
     * 估值版本状态：
     * DRAFT 草稿（登记未提交，可改可删不生效）
     * / PENDING_APPROVAL 待双人审批（金额修订已提交，第一签已到/未到）
     * / ACTIVE 当前生效 / SUPERSEDED 已被新版本取代 / REJECTED 双人审批驳回。
     * 只追加状态流转，任何旧版本永不物理删除、永不被原地改写。
     */
    public static final String VAL_DRAFT = "DRAFT";
    public static final String VAL_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String VAL_ACTIVE = "ACTIVE";
    public static final String VAL_SUPERSEDED = "SUPERSEDED";
    public static final String VAL_REJECTED = "REJECTED";

    /** 金额修订双人审批签署位次：初审 / 复核（两次必须不同实签人） */
    public static final String VAL_APPROVAL_FIRST = "FIRST";
    public static final String VAL_APPROVAL_SECOND = "SECOND";

    /** 保险批单状态：ISSUED 已出具（估值新版本生效时一并生成，不可变） */
    public static final String ENDORSEMENT_ISSUED = "ISSUED";

    /** 过期提醒状态：OPEN 待处理 / ACKNOWLEDGED 已确认（提醒只提示，不冻结归还） */
    public static final String VAL_REMIND_OPEN = "OPEN";
    public static final String VAL_REMIND_ACKED = "ACKNOWLEDGED";

    /**
     * 估值访问角色：
     * VALUATION_OFFICER 估值经办（登记/提交/全字段）；FINANCE 财务（只看金额与凭证）；
     * LIBRARIAN 普通馆员 / PUBLIC 公众（敏感估值一律不可见，入口直接拒绝）。
     */
    public static final String ROLE_VALUATION_OFFICER = "VALUATION_OFFICER";
    public static final String ROLE_FINANCE = "FINANCE";
    public static final String ROLE_PUBLIC = "PUBLIC";

    /**
     * 台账流水动作：估值登记/提交修订/双人审批签署/驳回/新版本生效/保险批单出具/
     * 借展签约快照/过期提醒/提醒确认/越权访问拒绝。
     */
    public static final String ACTION_VAL_DRAFT = "VALUATION_DRAFT";
    public static final String ACTION_VAL_SUBMIT = "VALUATION_SUBMIT";
    public static final String ACTION_VAL_APPROVE = "VALUATION_APPROVE";
    public static final String ACTION_VAL_REJECT = "VALUATION_REJECT";
    public static final String ACTION_VAL_ACTIVATE = "VALUATION_ACTIVATE";
    public static final String ACTION_VAL_ENDORSEMENT = "INSURANCE_ENDORSEMENT";
    public static final String ACTION_VAL_SNAPSHOT = "VALUATION_LOAN_SNAPSHOT";
    public static final String ACTION_VAL_EXPIRE_REMIND = "VALUATION_EXPIRE_REMIND";
    public static final String ACTION_VAL_REMIND_ACK = "VALUATION_REMIND_ACK";
    public static final String ACTION_VAL_ACCESS_DENY = "VALUATION_ACCESS_DENY";
    public static final String ACTION_VAL_POLICY_TIGHTEN = "VAL_PERMIT_TIGHTEN";
    public static final String ACTION_VAL_POLICY_RELAX = "VAL_PERMIT_RELAX";

    // --------------------------------------------------------------------
    // museum-16 文物修复年度报表
    // --------------------------------------------------------------------

    /** 指标口径版本状态：草稿 / 当前生效 / 已被新版本取代（只追加不改写） */
    public static final String CALIBER_DRAFT = "DRAFT";
    public static final String CALIBER_ACTIVE = "ACTIVE";
    public static final String CALIBER_SUPERSEDED = "SUPERSEDED";

    /** 年度报表状态：已生成归档快照 / 已撤回（快照与凭证全部保留） */
    public static final String ANNUAL_REPORT_GENERATED = "GENERATED";
    public static final String ANNUAL_REPORT_REVOKED = "REVOKED";

    /** 生成触发方式：手动 / Quartz 定时 */
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    /** 生成任务日志状态：进行中 / 成功 / 失败（日志保留不删） */
    public static final String JOB_RUNNING = "RUNNING";
    public static final String JOB_SUCCESS = "SUCCESS";
    public static final String JOB_FAILED = "FAILED";

    /** 失败告警状态：待处理 / 已确认 */
    public static final String ANNUAL_ALERT_OPEN = "OPEN";
    public static final String ANNUAL_ALERT_ACKED = "ACKNOWLEDGED";

    /** 报表异常凭证类型：环境异常事件 / 修复设备故障 */
    public static final String ANOMALY_ENV_EVENT = "ENV_EVENT";
    public static final String ANOMALY_EQUIPMENT_FAULT = "EQUIPMENT_FAULT";

    /**
     * 导出人保密许可角色：REPORT_ADMIN 报表管理员（可导出全部密级明细）；
     * REPORT_READER 普通报表查阅人（保密一级文物明细整列剔除，只见汇总与二/三级）。
     */
    public static final String ROLE_REPORT_ADMIN = "REPORT_ADMIN";
    public static final String ROLE_REPORT_READER = "REPORT_READER";

    /**
     * 台账流水动作：口径版本发布 / 年度报表生成 / 撤回 / 撤回后重提
     * / 异常凭证追加 / 生成任务失败告警 / 告警确认。
     */
    public static final String ACTION_ANNUAL_CALIBER_PUBLISH = "ANNUAL_CALIBER_PUBLISH";
    public static final String ACTION_ANNUAL_REPORT_GENERATE = "ANNUAL_REPORT_GENERATE";
    public static final String ACTION_ANNUAL_REPORT_REVOKE = "ANNUAL_REPORT_REVOKE";
    public static final String ACTION_ANNUAL_REPORT_RESUBMIT = "ANNUAL_REPORT_RESUBMIT";
    public static final String ACTION_ANNUAL_ANOMALY_APPEND = "ANNUAL_ANOMALY_APPEND";
    public static final String ACTION_ANNUAL_JOB_FAILED = "ANNUAL_JOB_FAILED";
    public static final String ACTION_ANNUAL_ALERT_ACK = "ANNUAL_ALERT_ACK";
}
