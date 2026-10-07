# EVOPS 基础工作区

这是 springboot 的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

`docs/schema/evops.sql` 是交付副本，与工作区 `src/main/resources/schema.sql` 保持一致（骨架原约定的
`../../docs/schema/evops.sql` 在当前环境根目录无写权限，故置于工作区内）。

## 博物馆文物修复台账（museum-1）

在骨架上新增的业务模块，**不含票务模块**。

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_relic` | 文物档案：文物编号、材质、年代、来源、保存等级（ONE/TWO/THREE）；撤销只置 `REVOKED` 状态 |
| `t_relic_intake` | 入藏登记：尺寸（高宽厚、重量）、库位、入藏时间，一件文物一条 |
| `t_relic_photo` | 入藏关联照片，一张照片一行 |
| `t_repair_project` | 修复项目：项目编号、委托人、修复方案；状态机见下 |
| `t_repair_stage` | 修复阶段结果，阶段号项目内递增 |
| `t_repair_acceptance` | 验收记录，一个项目一条 |
| `t_relic_loan` | 借出/归还单，一条记录贯通一次借还 |
| `t_relic_ledger` | 台账流水：入藏/借出/归还/修复/验收/撤销/作废/影像归档/权限调整/材料入库领用退料复核全部追加留痕 |
| `t_bootstrap_lock` | 起步幂等锁，固定单行 id=1 |
| `t_photo_file` | 影像物理文件，SHA-256 内容寻址、全库唯一一份；作废不删文件 |
| `t_photo_batch` | 拍摄批次：一次拍摄的正面/背面/底部/细部照片归一批次 |
| `t_relic_image` | 文物影像版本引用：一张照片一行，跨修复阶段形成新版本 |
| `t_permission_policy` | 文物修复权限开关（单行 id=1），收紧时影像上传停止 |
| `t_material` | 修复材料目录：颜料 PIGMENT / 黏合剂 ADHESIVE / 保护剂 PROTECTANT |
| `t_material_batch` / `t_material_inbound` | 材料批号库存与入库流水，按批号管理、同批号追加入库累加库存 |
| `t_material_usage` / `t_material_usage_line` | 工单材料领用单与明细行：批号、计划/实际用量、部位、操作者（行内快照） |
| `t_material_return` | 退料单，必须关联原领用单与原明细行，库存加回原批号 |
| `t_material_review` | 用量差异复核，每条超阈值明细行全生命周期唯一一条（定时任务幂等锚点） |
| `t_hall` / `t_hall_relic` | 展厅与在展陈展（点位+材质快照），巡检任务按展厅生成，撤展只置状态 |
| `t_env_rule_version` / `t_env_rule_item` | 环境巡检规则版本与材质条目（温湿度/照度阈值、采样频率），发布新版本旧版本置 `SUPERSEDED` 只追加 |
| `t_inspection_task` | 巡检任务：按展厅一轮一行，固化规则版本快照，`(hall_id, round_no)` 幂等 |
| `t_env_reading` / `t_env_reading_detail` | 点位读数与按材质判定明细：阈值/版本随读数固化，历史不随后续版本改写 |
| `t_env_event` / `t_env_event_photo` | 异常事件（按展厅+点位+指标+材质归并连续超限）与现场照片，关闭须处置人+照片+保管员复核 |
| `t_env_ledger` | 环境台账：规则发布/任务生成/读数/事件开闭处置复核全部只追加留痕、标明规则版本 |
| `t_loan_agreement` / `t_loan_agreement_version` | 借展协议主单与版本：借展方/保险金额/运输路线/归还日期随版本固化，变更只追加新版本，已签版本置 `SUPERSEDED` 不覆盖 |
| `t_loan_manifest_line` | 借展清单：随协议版本固化的出库清单快照，归还验收按出库版本清单逐项比对 |
| `t_loan_escort_node` | 押运节点：封箱/交接/到馆/开箱四类唯一、按序登记，异常暂停、开箱双人见证 |
| `t_loan_return_check` / `t_loan_return_check_line` | 归还验收头与逐行比对（相符/损伤/缺失），差异永久保留，一协议一条验收 |
| `t_pest_rule_version` / `t_pest_rule_item` | 虫种阈值规则版本与条目：发布新版本旧版本置 `SUPERSEDED` 只追加，监测记录旧结论不改写 |
| `t_pest_trap_point` / `t_pest_point_move` | 展厅/库房诱捕点位（编号/布放日期/坐标/责任区域）与迁移记录（前后坐标只追加） |
| `t_pest_trap_card` | 诱捕片：更换只置 `REPLACED`，旧片与旧监测记录永不删除 |
| `t_pest_monitor_record` / `t_pest_monitor_detail` / `t_pest_monitor_photo` | 监测记录（规则版本快照+结论固化）/ 虫种数量明细（阈值快照）/ 现场照片（内容寻址） |
| `t_pest_dispose_task` | 超阈值处置任务：关联责任区域，外包人员不能关闭 |
| `t_pest_month_freeze` | 监测月份冻结（一月一行只追加）：冻结后该月记录与照片不再接受补录 |
| `t_pest_ledger` | 虫害台账：规则发布/点位/诱捕片/监测/任务/冻结全部只追加留痕 |

约束：文物编号 `uk_relic_no`、项目编号 `uk_project_no` 数据库层唯一（含已撤销/已作废记录）；
撤销与作废均不删除任何数据。

### 状态链路

- 修复项目：`PLANNED → REPAIRING → REPAIRED → ACCEPTED`；`PLANNED/REPAIRING` 可 `CANCELLED`。
- 借出：`LENT → RETURNED`（在原单上更新，不新增记录）。
- 借展协议：`DRAFT → SIGNED → IN_TRANSIT → RETURNED`（`CANCELLED` 为旁路终态）；
  押运节点固定 `封箱 SEAL → 交接 HANDOVER → 到馆 ARRIVAL → 开箱 OPEN_BOX`，任一节点异常则暂停后续操作。
- 跨业务校验：文物借出未还不能重复借出、不能开工修复；有未闭环借还/项目/借展协议的文物不能撤销。

### 主要接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/bootstrap` | **博物馆文物修复档案起步**：首次播种一条走完全链路的有效样本 |
| `GET /api/bootstrap` | 只读查看起步样本（未起步为 null） |
| `POST /api/relics` | 文物建档（编号重复返回失败） |
| `GET  /api/relics?status=&keyword=` | 档案列表 |
| `GET  /api/relics/{id}` | 完整档案：档案 + 入藏照片 + 借还 + 项目 + 台账流水 |
| `POST /api/relics/{id}/intake` | 入藏登记（照片/尺寸/库位，至少一张照片） |
| `POST /api/relics/{id}/revoke` | 撤销（只改状态，记录保留） |
| `POST /api/loans` / `POST /api/loans/{id}/return` | 借出 / 归还 |
| `POST /api/repair-projects` | 修复立项（委托人、方案，项目编号唯一） |
| `POST /api/repair-projects/{id}/start` | 开工 |
| `POST /api/repair-projects/{id}/stages` | 登记阶段结果（可多次） |
| `POST /api/repair-projects/{id}/finish` | 修复完成 |
| `POST /api/repair-projects/{id}/accept` | 验收（PASS/CONDITIONAL） |
| `POST /api/repair-projects/{id}/cancel` | 作废 |

## 文物多角度影像归档（museum-2）

正面（FRONT）、背面（BACK）、底部（BOTTOM）、细部（DETAIL）照片按**拍摄批次**归档。

### 归档规则

- **逐张校验**：上传时逐张校验分辨率（默认不低于 800×800，可由 `museum.image.min-*-px` 调整）、
  拍摄时间（必填、格式 `yyyy-MM-ddTHH:mm:ss` 或 `yyyy-MM-dd HH:mm:ss`、不得晚于当前时间）、
  摄影者（必填）。批量上传中任意一张失败都不影响其余张，响应逐张给出具体原因。
- **同文件去重**：物理文件按 SHA-256 内容寻址落盘（`ab/cd/<hash>.ext`），同一文件重复上传
  只保留一份物理文件、新增一条影像引用（响应中 `duplicateFile=true`）。
- **修复阶段新版本**：同一修复阶段内补拍沿用当前版本号；换一个修复阶段（或非修复场景的新批次）
  必须形成新版本，版本号文物内从 1 递增，只追加、不覆盖。
- **作废不删原文件**：单张/整批作废只把引用与批次置 `VOID` 并登记原因、写台账；物理文件与引用行
  永久保留、可查不可再下载。
- **下载按岗位脱敏**：`RESTORER`（修复师）取原件；`CURATOR`（馆藏保管员）取长边≤1280 加水印的
  JPEG 副本；`VIEWER`（观摩访客/未声明岗位）取长边≤640 的更强脱敏副本，且版本清单中摄影者姓名
  脱敏为「姓\*\*」。原文件不对低权限岗位出库。
- **权限收紧闸门**：`PUT /api/policy/repair-photo-upload {"open":false}` 收紧文物修复权限后，
  影像上传在入口处整体停止、不产生任何批次/版本/文件；已确认的影像版本、物理文件与台账事实
  全部保留，仍可查询与下载。`open=true` 放开后继续按新版本归档。

### 影像接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/relics/{id}/image-batches` | multipart 批量上传一个拍摄批次；`files` 与同序的 `viewTypes/shotAts/photographers`，可选 `projectId/stageId/shotLocation/remark` |
| `GET  /api/relics/{id}/image-versions?role=` | 影像版本清单（按版本分组，按岗位脱敏摄影者） |
| `GET  /api/image-batches/{batchId}?role=` | 拍摄批次详情（批次头 + 逐张影像） |
| `GET  /api/images/{imageId}/download?role=` | 按岗位下载（原件或脱敏副本） |
| `POST /api/images/{imageId}/void` | 作废单张影像（原文件保留） |
| `POST /api/image-batches/{batchId}/void` | 作废整个拍摄批次 |
| `GET  /api/policy/repair-photo-upload` | 查看影像上传权限开关 |
| `PUT  /api/policy/repair-photo-upload` | 收紧（`open=false`）/ 放开（`open=true`），写台账留痕 |

批量上传响应示例（节选）：`versionNo`、`successCount/failedCount`，以及与文件同序的 `items`，
每项含 `index/fileName/viewType/success/duplicateFile/imageId/fileId/reason`。

## 修复材料批号与用量核算（museum-3）

颜料（PIGMENT）、黏合剂（ADHESIVE）和保护剂（PROTECTANT）**按批号入库**；修复工单
（仅 `REPAIRING` 状态可领料）领用时逐行记录**批号、计划用量、实际用量、使用部位与操作者**。

### 核算规则

- **过期材料不能领用**：批号 `expire_date` 早于领用当天即拒绝（允许入库登记临期/过期批号，
  但领用时按当天校验）；非在库（用尽/报废）批号同样禁领。
- **库存与修复进度一致**：库存按批号行管理，领用条件 UPDATE 原子扣减、整单任一失败整体回滚；
  随时可按批号对账：应有库存 = 累计入库 − 累计领用 + 累计退料。
- **历史工单不受批号变更影响**：明细行在领用时固化材料名称、批号、类别、单位、操作者快照，
  材料目录更名/改类或批号后续变化都不改写历史事实。
- **退料关联原领用单**：必须指定原单与原明细行，允许分次退但累计不得超过实际用量；
  库存加回原批号；整单全部退齐时状态 `ISSUED → RETURNED`。退料只追加，不覆盖原领用事实。
- **差异触发复核**：实际相对计划偏差严格超过阈值（`museum.material.variance-threshold-pct`，
  默认 20%）的明细行置 `PENDING`。
- **定时任务重启幂等**：差异复核扫描器在应用就绪时补扫一次、之后按
  `review-scan-interval-ms` 周期扫描；以 `t_material_review.uk_review_line` 为锚点，
  同一明细行**只产生一条复核记录、一条台账流水**——重启重扫**不多写记录**；操作者从原
  领用行原样带入复核单与台账，**不丢操作者**。扫描串行加锁，启动补扫与周期轮次不重入。
- **复核闭环**：`OPEN → CONFIRMED` 后解除行/单的待复核状态；确认人单列，原操作者不被覆盖。

### 材料接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/materials` | 材料建档（颜料/黏合剂/保护剂，编号唯一） |
| `GET  /api/materials?category=` | 材料目录，可按类别过滤 |
| `POST /api/materials/inbound` | 按批号入库（同批号追加入库累加库存、追加入库流水） |
| `GET  /api/materials/batches?materialId=&usable=` | 批号库存列表；`usable=true` 只看在库未过期 |
| `GET  /api/materials/batches/{id}/reconcile` | 批号库存对账 |
| `POST /api/material-usages` | 工单领用（逐行批号/计划实际用量/部位/操作者） |
| `GET  /api/material-usages/{id}` | 领用单详情（单头 + 明细 + 退料） |
| `GET  /api/material-usages?projectId=` | 按工单查领用 |
| `POST /api/material-returns` | 退料（关联原领用单/明细行） |
| `GET  /api/material-reviews?status=` | 差异复核清单 |
| `POST /api/material-reviews/scan` | 手动触发一轮幂等补扫 |
| `POST /api/material-reviews/{id}/confirm` | 复核确认（不覆盖原操作者） |

## 修复方案会签（museum-4）

修复方案按**版本**管理：每次提交一个新版本（版本号项目内递增），由馆内专家（INTERNAL）、
外部专家（EXTERNAL）、保管人员（CUSTODIAN）**依次会签**；全部同意才允许开工生成施工任务。

### 会签规则

- **签名顺序按文物风险配置**：提交版本时取文物保存等级快照 `riskLevel`，会签顺序由
  `museum.countersign.sign-order` 按等级决定（默认一级：外部专家→馆内专家→保管人员；
  二级：馆内→外部→保管；三级：馆内→保管→外部）。乱序签署一律拒绝。
- **退回即只读**：任一专家退回（RETURN）后版本封存为 `RETURNED`，不再接受任何签名；
  修订只能另开新版本，旧版本及其会签记录永不改写。
- **意见、签名时间、依据附件留痕**：每条会签记录保存实签人、决定、意见、
  `signedAt` 签名时间与依据附件引用，只追加不修改。
- **缺席专家不能代签**：实签人必须与版本指定的应签人完全一致，
  管理员或他人代签一律拒绝，系统不提供代签入口。
- **会签完成才允许开工**：`POST /api/repair-projects/{id}/start` 要求项目最新方案版本
  为 `APPROVED`，否则拒绝生成施工任务；开工后不允许再提交新版本。
- **版本归属同一口径**：签名永远挂在"签署时处于会签中的版本"上——版本退回/通过的
  临界时间之后，任何签名都不能再归入已封存版本，也不会混入新版本；
  `(plan_version_id, step_no)` 数据库唯一，同版本同一步骤不可能出现两条签名。

### 会签接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/repair-projects/{id}/plan-versions` | 提交新方案版本（指定三类应签人） |
| `GET  /api/repair-projects/{id}/plan-versions` | 项目方案版本列表（含会签记录） |
| `POST /api/plan-versions/{versionId}/sign` | 会签签署（顺序/应签人由系统校验） |
| `GET  /api/plan-versions/{versionId}` | 版本详情（版本 + 会签顺序 + 会签记录） |

### 起步幂等

`POST /api/bootstrap` 首次调用播种样本 `WW-2026-0001`（入藏 `RU-2026-0001` → 借出/归还
`LO-2026-0001` → 修复项目 `RP-2026-0001`，方案会签（一级：外部专家→馆内专家→保管人员）
通过后开工，含两个修复阶段与验收）；开工后另播种三类修复材料
（颜料 `B-2026-0001` / 黏合剂 `B-2026-0002` / 保护剂 `B-2026-0003` 批号入库）→ 工单领用
`MU-2026-0001`（三行用量，黏合剂行偏差 60% 触发一条差异复核）→ 退料 `MR-2026-0001`。

幂等保证：播种与 `t_bootstrap_lock`（单行、CHECK id=1）在同一事务提交；重复请求直接回读同一行返回
`created=false`，不会产生第二条文物/项目/台账记录；并发请求靠主键冲突整体回滚，已通过 8 并发测试验证。

### 测试

```bash
mvn test
```

- `BootstrapApiTest`：起步两次只产生一条全链路样本，台账动作顺序完整。
- `ConcurrentBootstrapTest`：8 并发同时起步，仅一次播种，全部命中同一条样本。
- `MuseumLedgerRuleTest`：编号唯一、入藏不重复、借还/修复/验收状态机不可跳步、撤销不抹除记录。
- `ImageArchiveTest`：四视角批次归档；分辨率/拍摄时间/摄影者/视角逐张返回失败原因；
  同文件重复上传磁盘仅一份；不同修复阶段形成新版本、同阶段补拍沿用版本；
  作废不删原文件；修复师取原件、保管员/访客取脱敏副本；权限收紧时上传停止且已确认版本保留。
- `MaterialAccountingTest`：三类材料按批号入库、同批号追加累加；过期/用尽批号禁领且失败整单回滚；
  领用记录用量/部位/操作者并原子扣减库存；差异超阈值触发复核，反复扫描（模拟重启）只产生一条复核、
  一条台账且保留操作者；退料关联原领用单、超额拒退、退齐整单关闭；材料更名不改历史工单快照；
  库存按「入库−领用+退料」全程对平。
- `RepairPlanCountersignTest`：会签顺序按风险等级配置（一级外部专家先签、三级外部专家末签）；
  乱序/代签一律拒绝；退回后旧版本只读、修订只能开新版本；意见/签名时间/依据附件留痕；
  会签未完成禁止开工、全部通过后允许开工；版本封存临界前后签名始终归属签署时的在签版本。

## 展厅温湿度与照度巡检（museum-5）

展厅按**点位**对温度、相对湿度、照度三项指标巡检。规则按**材质**配置阈值与采样频率、
按**版本**发布；巡检任务**按展厅**生成；异常必须关联**现场照片与处置人**，
**连续超限合并为同一事件**，事件关闭前必须由**保管员复核**；报表与台账均**标明规则版本**。

### 巡检规则

- **不同材质不同阈值与频率**：规则版本中每个材质一条目
  （温度/湿度/照度上下限 + `sampleIntervalMin` 采样频率，如纸质 60 分钟、青铜 120 分钟）。
  按展厅生成任务时，节拍取展厅内在展文物各材质频率的**最小值**（最严格者决定巡检密度）；
  全部材质均无条目时用 `museum.env.default-interval-min` 兜底。
- **规则按版本发布，只追加不改写**：`POST /api/env-rules/publish` 发布新版本后旧生效版本置
  `SUPERSEDED`（内容永不改写）。任务生成时固化 `rule_version_id/no` 快照，读数按**任务的版本**
  判定并把版本与各材质阈值固化在读数及 `t_env_reading_detail` 明细中——**新版本不改写任何
  已生成/已完成巡检**，任务/读数一经完成不可补改。
- **按展厅生成任务**：定时扫描（`museum.env.generate-interval-ms`，启动就绪补扫一轮）与
  手动 `POST /api/inspection/tasks/generate?force=true` 同一口径；每展厅轮次号递增，
  `(hall_id, round_no)` 数据库唯一，且每个展厅同时只有一个 `PENDING` 轮次，重复触发不生成重复任务。
- **逐材质判定**：一个点位可展陈多种材质，读数按各材质条目分别判定、分别固化阈值明细；
  点位在展但规则无条目的材质标记为 `uncoveredMaterials` 不猜测阈值；读数必须覆盖全部在展点位、
  点位不允许重复。
- **连续超限合并**：事件归并键 `展厅|点位|指标|材质`（`merge_key` 唯一，仅 `OPEN/HANDLING`
  在途事件持有）。同一材质同一指标连续越限只更新同一事件的末次读数/峰值/累计次数，不新开；
  指标恢复正常后事件转 `RESOLVED` 并释放归并键，再次越限另开新事件。
- **异常关联现场照片与处置人**：现场照片走影像归档 SHA-256 内容寻址（同文件只一份，
  不套用 800×800 归档下限）；事件关闭前必须已登记处置人且至少一张现场照片，否则保管员无法关闭。
- **保管员复核关闭**：仅 `RESOLVED`（指标已恢复）事件可复核；复核通过才 `CLOSED` 封存，
  复核不通过退回 `HANDLING`，系统停留待处理，须重新处置并待指标再次恢复后复核。
- **待处理与可追溯**：修复审批只完成一半（方案仍 `SIGNING`）时禁止开工、停留待处理，
  不影响巡检侧；规则发布、任务生成、每条读数、事件开/并/恢复/处置/驳回/关闭全部追加写入
  `t_env_ledger`，任务/读数/事件报表都带规则版本号，环境指标与巡检计划全程可追溯。

### 事件状态链路

`OPEN（连续超限并入）→ HANDLING（已登记处置人）→ RESOLVED（指标恢复待复核）→ CLOSED（保管员复核）`

- `OPEN` 登记处置人即转 `HANDLING`；处置中仍接受连续超限并入。
- 保管员在非 `RESOLVED` 状态复核一律拒绝（系统停留待处理）。
- 复核不通过：退回 `HANDLING`；现场持续恢复后系统自动重新置回 `RESOLVED`，不留死事件。

### 巡检接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/halls` / `GET /api/halls` / `GET /api/halls/{id}` | 展厅建档 / 列表 / 详情（含在展文物点位材质） |
| `POST /api/halls/{id}/relics` / `POST /api/halls/{id}/relics/{relicId}/withdraw` | 文物按点位上展 / 撤展（历史保留） |
| `POST /api/env-rules/publish` | 发布规则新版本（旧版本自动 `SUPERSEDED`） |
| `GET /api/env-rules` / `GET /api/env-rules/active` / `GET /api/env-rules/{id}` | 版本列表 / 当前生效版本 / 版本材质条目 |
| `POST /api/inspection/tasks/generate?force=true` | 手动触发一轮按展厅生成（定时任务同口径，幂等） |
| `GET /api/inspection/tasks?hallId=&status=` | 巡检任务列表（每项标明规则版本） |
| `POST /api/inspection/tasks/{id}/readings` | 提交一轮各点位温湿度/照度读数（覆盖全部点位） |
| `GET /api/inspection/tasks/{id}/report` | 任务报表：任务+读数+按材质阈值明细，标明规则版本 |
| `GET /api/env-events?hallId=&status=&pointCode=&material=` | 异常事件列表（可按状态/点位/材质过滤） |
| `GET /api/env-events/{id}` | 事件报表：事件头（首警规则版本）+ 现场照片 |
| `POST /api/env-events/{id}/handle` | 登记处置人与处置说明 |
| `POST /api/env-events/{id}/photos` | multipart 上传现场照片（内容寻址去重，关闭前至少一张） |
| `POST /api/env-events/{id}/review` | 保管员复核：通过关闭 / 不通过退回处置中 |
| `GET /api/env-ledgers?hallId=` | 环境台账（不传展厅为全馆；规则发布挂全馆） |

### 测试

`EnvInspectionTest`（独立内存库）覆盖：三类材质不同阈值/频率、任务节拍取最小频率；
任务/读数/事件固化版本快照，发布 v2 后历史任务与读数仍按 v1 阈值；连续两轮超限合并为一个事件、
恢复后再次超限另开新事件；关闭前缺处置人或缺照片均拒绝、保管员复核通过才关闭、驳回停留待处理；
同点位纸/青铜同时越限分别成事件；十轮任务与环境台账全程标明规则版本可追溯；
修复方案会签只完成一半时禁止开工、巡检侧任务与指标不受影响。

## 文物借展协议与押运闭环（museum-6）

借展按**协议**登记借展方、保险金额、运输路线与归还日期；协议变更按**版本**只追加；
押运按**封箱 → 交接 → 到馆 → 开箱**四个节点推进；归还按**原借展清单逐项验收**并保留差异。

### 借展规则

- **协议登记**：借展方、保险金额（不可为负）、运输路线、约定归还日期与借展清单（至少一行）必填；
  同一文物同时只允许一条未闭环（未归还验收/未取消）协议；协议首版为 `DRAFT`，签署后才生效。
- **协议变更只出新版**：变更复制当前版本条款与清单生成新版本（`version_no` 协议内递增，变更原因必填）；
  新版本签署时旧 `SIGNED` 版本置 `SUPERSEDED`，**已签协议不能直接覆盖**，旧版本与清单快照永不改写；
  新版本签署前押运节点一律暂停。
- **押运节点按序推进**：封箱 `SEAL` → 交接 `HANDOVER` → 到馆 `ARRIVAL` → 开箱 `OPEN_BOX`，
  每类节点协议内仅一条，跳序/重复登记均拒绝；节点固化登记时的协议版本与处理人。
- **任一节点异常即暂停**：节点登记异常必须填异常原因，此后后续节点全部拒绝登记，
  须先解除异常（登记解除人与解除说明）才能继续；异常原因、处理人、解除人全部留痕。
- **开箱双人见证**：开箱必须登记两名**不同**见证人，单人或同名一律拒绝。
- **封箱贯通既有占用口径**：首节点封箱时向 `t_relic_loan` 写一条 `LENT` 记录（单号取协议号），
  押运在途期间修复开工、文物撤销、旧通道重复借出继续被 museum-1 跨业务校验拦截。
- **归还逐项验收**：开箱完成且异常全部解除后，按**封箱出库时签署版本的原清单**逐行比对，
  漏项/多项/重复均拒绝；每行给 `INTACT 相符 / DAMAGED 损伤 / MISSING 缺失`，
  非相符行必须填差异说明；结论全部相符 `ALL_MATCH`，否则 `WITH_DIFF`，差异逐行永久保留；
  验收闭环后协议置 `RETURNED`，贯通借出记录同步归还。
- **修复侧反查**：`GET /api/loan-agreements/trace?projectId=` 由修复项目经文物反查借展原单，
  返回原单号、当前版本、借展方/保险金额/路线/归还日期、四节点处理人及**异常原因/解除人**、验收差异。
- 协议登记/签署/变更、节点登记/异常/解除、归还验收全部追加写入 `t_relic_ledger` 留痕。

### 借展接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/loan-agreements` | 协议登记（借展方/保险金额/运输路线/归还日期 + 清单） |
| `GET  /api/loan-agreements?relicId=&status=` | 协议列表 |
| `GET  /api/loan-agreements/{id}` | 协议详情：主单 + 全部版本与清单 + 押运节点 + 归还验收 |
| `POST /api/loan-agreements/{id}/sign` | 签署当前待签版本（旧版自动 `SUPERSEDED`） |
| `POST /api/loan-agreements/{id}/amend` | 协议变更，生成待签新版本（已签内容不覆盖） |
| `POST /api/loan-agreements/{id}/escort-nodes` | 登记押运节点（封箱/交接/到馆/开箱；异常暂停、开箱双人见证） |
| `POST /api/escort-nodes/{nodeId}/resolve` | 押运异常解除，后续节点恢复 |
| `POST /api/loan-agreements/{id}/return-check` | 归还验收（与原清单逐项比对，差异保留） |
| `GET  /api/loan-agreements/trace?projectId=` | 修复侧反查：原单、节点处理人、异常原因与验收差异 |

### 测试

`LoanExhibitionTest`（独立内存库）覆盖：登记必填与重复协议拦截；未签不能押运、已签只能变更出新版；
四节点跳序/重复拒绝；节点异常暂停、解除后恢复且异常原因/处理人/解除人可查；
开箱单人/同名见证人拒绝、两名不同见证人通过；归还验收漏项/多项拒绝、损伤与缺失差异逐行保留、
贯通借出记录同步归还；修复项目反查定位原单与异常原因；在途期间修复开工与文物撤销被拦截；
台账动作链完整可追溯。

## 文物库房库位与密集架预约（museum-7）

库房按**库区 → 密集架 → 格位**三级维护：库位按**温区**（恒温 CONST_TEMP / 冷藏 COLD /
常温 ORDINARY）、**防火等级**（F1/F2/F3）与**承重**建档；文物/箱体搬入前必须先
**预约目标格位**；**移动单**固化原位、目的位、箱号与操作人员。

### 预约与移动规则

- **搬入前必须预约**：预约登记目标格位、箱号、计划操作时段与预约人，格位承重超限、
  格位停用、原格位未存放该箱（库内移动）一律拒绝；同一格位同时只有一个在途预约
  （`hold_key` 稀疏唯一约束，终态置空）。
- **超时自动释放，但不释放已开工格位**：预约 `expire_at = 预约时间 + reservation-expire-minutes`
  （默认 30 分钟，可在请求中以 `expireMinutes` 覆盖）；定时扫描（启动补扫 + 周期扫描，
  串行不重入）只把**仍为 RESERVED 且已到期**的单置 EXPIRED 并清格位持有键，
  **WHERE 不含 MOVING——已开始移动的格位永不释放**，释放后格位可再约。
- **互斥设备同时段单一操作**：以一台密集架为互斥单位，同一架体上时间窗重叠
  （`start < 存在.end AND end > 存在.start`）的在途预约/移动只允许一个，时间端点相接、
  不同架体并行放行；架体行 UPDATE 占位锁把同架并发串行化，重叠判定在锁内复查。
- **移动单留痕**：开工（RESERVED→MOVING）登记操作人员并生成移动单，固化原位/目的位
  /箱号/操作人员快照；完成（MOVING→COMPLETED）在 `t_storage_stock` 原子清原格、立目的格；
  已开工不可取消；开工/完成/取消重复调用幂等，不产生第二条。
- **补传/补录/重试只形成一条结果**：预约以请求流水号 `requestNo` 为幂等锚点（全库唯一），
  同号重复提交（含并发）命中同一条预约并原样返回，绝不产生第二条可用结果。
- **库存查询按馆员权限隐藏敏感位置**（`?role=`，沿用 museum-2 传参方式）：
  `WAREHOUSE_KEEPER` 库房管理员见全部精确位置；`CURATOR` 馆藏保管员对敏感格位保留库存
  事实但精确位置脱敏（`masked=true`、`locationName="敏感库位"`，格位/架/库区/层列置空）；
  `VIEWER`（访客/未声明角色）直接过滤掉敏感格位。
- 建档停用、预约/取消/超时释放、移动开工/完成全部只追加写入 `t_storage_ledger`。

### 库房接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/storage/areas` / `GET /api/storage/areas` / `POST /api/storage/areas/{id}/close` | 库区建档（温区/防火等级/承重）/ 列表 / 关闭 |
| `POST /api/storage/racks` / `GET /api/storage/racks?areaId=` | 密集架（互斥设备）建档 / 列表 |
| `POST /api/storage/cells` / `GET /api/storage/cells?areaId=&rackId=` / `POST /api/storage/cells/{id}/disable` | 格位建档（承重上限/敏感标记，温区防火默认随库区）/ 列表 / 停用 |
| `POST /api/storage/reservations` | 预约目标格位（`requestNo` 幂等；可选 originCellId/relicId/weightKg/expireMinutes） |
| `GET  /api/storage/reservations?status=&requestNo=&cellId=` | 预约列表 |
| `GET  /api/storage/reservations/{id}` | 预约详情：预约 + 移动单 + 库房台账 |
| `POST /api/storage/reservations/{id}/start` | 开工移动（操作人员），生成移动单 |
| `POST /api/storage/reservations/{id}/complete` | 完成移动，库存清原格立目的格 |
| `POST /api/storage/reservations/{id}/cancel` | 取消预约（MOVING 不可取消） |
| `POST /api/storage/reservations/expire-scan` | 手动触发一轮超时释放（定时任务同口径，MOVING 不释放） |
| `GET  /api/storage/move-orders?rackId=&boxNo=` | 移动单列表 |
| `GET  /api/storage/stock?role=&areaId=` | 库存查询，按馆员权限隐藏/脱敏敏感位置 |

### 测试

`StorageReservationTest`（独立内存库）覆盖：温区/防火等级/承重维护与非法枚举、超重、
停用拦截；预约在途唯一、请求流水号补传/补录/重试只一条；互斥设备同架重叠拒绝、错峰与
不同架放行；移动单原位/目的位/箱号/操作人员齐全、完成后库存清原格立目的格、重复调用幂等；
超时自动释放后格位可再约、**MOVING 到期不释放且继续占格**；访客过滤、保管员脱敏、
管理员全见的三档权限；台账动作链可追溯。
`StorageReservationConcurrentTest` 覆盖：8 并发约同格位仅 1 成功、8 并发同架重叠仅 1 成功、
8 并发同一流水号全部命中同一条。

## 虫害监测（museum-8）

展厅与库房设置**诱捕点位**，监测记录登记**虫种数量与照片**；虫种阈值规则按**版本**发布，
监测结论随记录固化；**超阈值生成处置任务并关联责任区域**；**更换诱捕片不删除旧记录**；
**点位迁移保留前后坐标**；**监测结果按月份冻结**；**外包人员只能提交现场数据，不能关闭任务**。

### 监测规则

- **点位建档**：展厅（`HALL`，引用 `t_hall`）与库房（`STORAGE`，引用 `t_storage_area`）均可布放；
  点位编号全库唯一，登记布放日期、坐标与责任区域；区域名称快照自展厅/库区带入。
- **虫种阈值规则按版本发布，只追加不改写**：`POST /api/pest-rules/publish` 发布新版本后
  旧生效版本置 `SUPERSEDED`（内容永不改写）。监测记录提交时固化 `rule_version_id/no` 快照、
  逐虫种阈值快照与判定结论——**规则调整只能生成新版本，不能改动监测记录旧结论**。
- **监测提交**：外包人员（`OUTSOURCE`）与馆内人员（`INTERNAL`）均可提交现场数据；
  虫种数量逐条登记（同记录内虫种不可重复），规则无条目的虫种不猜测阈值、不参与判定；
  记录关联提交时点位的在布诱捕片。照片走影像归档 SHA-256 内容寻址（同文件只一份），
  同一记录内同照片只登记一次。
- **超阈值生成处置任务**：任一虫种数量严格大于阈值即判定 `OVER_THRESHOLD`，
  生成处置任务（一记录至多一任务）并关联点位**责任区域**快照。
- **更换诱捕片不删除旧记录**：布放新片时旧片置 `REPLACED`（含更换时间/更换人），
  旧片与其监测记录永久保留可查；旧监测记录仍指向旧片。
- **点位迁移保留前后坐标**：迁移写入 `t_pest_point_move`（from/to 坐标、迁移人、原因），
  点位行只保存当前坐标，迁移记录只追加。
- **监测结果按月份冻结**：`POST /api/pest-monitor/freeze` 冻结月份（`yyyy-MM`，幂等，
  重复冻结只一条）；冻结后该月监测记录与照片不再接受补录/更改，已冻结事实保留可查；
  冻结只追加，不解冻。
- **外包人员不能关闭任务**：`POST /api/pest-tasks/{id}/close` 仅 `INTERNAL` 可关闭，
  `OUTSOURCE` 一律拒绝；已关闭任务不可重复关闭。
- 规则发布、点位建档/迁移/停用、诱捕片布放/更换、监测提交、任务生成/关闭、月份冻结
  全部只追加写入 `t_pest_ledger`，尽量标明规则版本，虫害监测全程可追溯。

### 监测接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/pest-rules/publish` | 发布虫种阈值规则新版本（旧版本自动 SUPERSEDED） |
| `GET /api/pest-rules` / `GET /api/pest-rules/active` / `GET /api/pest-rules/{id}` | 版本列表 / 当前生效版本 / 版本虫种条目 |
| `POST /api/pest-points` / `GET /api/pest-points` / `GET /api/pest-points/{id}` | 点位建档（编号/布放日期/坐标/责任区域）/ 列表 / 详情（含诱捕片与迁移记录） |
| `POST /api/pest-points/{id}/move` | 点位迁移（保留前后坐标） |
| `POST /api/pest-points/{id}/retire` | 点位停用（历史保留） |
| `POST /api/pest-points/{id}/cards` / `GET /api/pest-points/{id}/cards` | 布放/更换诱捕片（旧片 REPLACED 不删除）/ 诱捕片列表 |
| `POST /api/pest-records` | 提交监测记录（虫种数量；外包/馆内均可；超阈值生成处置任务） |
| `GET  /api/pest-records?pointId=&month=&conclusion=` | 监测记录列表 |
| `GET  /api/pest-records/{id}` | 记录报表：记录头（标明规则版本）+ 虫种明细（阈值快照）+ 现场照片 |
| `POST /api/pest-records/{id}/photos` | multipart 上传现场照片（内容寻址去重；月份冻结后拒收） |
| `GET  /api/pest-tasks?pointId=&status=&responsibleArea=` | 处置任务列表（可按责任区域过滤） |
| `POST /api/pest-tasks/{id}/close` | 关闭处置任务（仅馆内人员；外包人员拒绝） |
| `POST /api/pest-monitor/freeze` / `GET /api/pest-monitor/freezes` | 冻结监测月份（幂等）/ 已冻结月份列表 |
| `GET  /api/pest-ledgers?pointId=` | 虫害台账（不传点位为全馆） |

### 测试

`PestMonitorTest`（独立内存库）覆盖：展厅与库房点位建档（编号唯一/布放日期/坐标/责任区域快照）、
未布片拒交、外包人员提交正常记录不生成任务、同记录虫种重复拒绝、照片内容寻址去重；
更换诱捕片旧片 REPLACED 且旧监测记录仍指向旧片；点位迁移前后坐标留痕；
超阈值生成处置任务并关联责任区域、规则无条目虫种不判定；外包人员关闭任务拒绝、
馆内人员可关闭、重复关闭拒绝；月份冻结后记录与照片拒收、重复冻结幂等、已冻结事实可查；
规则 v2 发布后旧记录结论与阈值快照不改写、新记录按 v2 判定并关联更换后在布诱捕片；
台账全动作留痕（迁移台账含前后坐标、任务台账关联责任区域）。

## 修复工时与设备排程（museum-9）

修复师按工单（修复项目）填报**起止时间、工序和设备**；**设备同一时段不能被两个工单占用**；
**跨天作业按自然日拆分统计**；工时提交后由**负责人审核**，**退回只能补充新版本**；
**设备故障自动生成改期建议但不悄悄改变原排程**；文物（修复对象）置为无效后，
「修复工时与设备占用排程」的**页面、导出、后台统计三端同口径**。

### 工时与排程规则

- **设备档案**：`t_repair_equipment` 编号全库唯一；状态 `ACTIVE/FAULT`，故障只置状态，
  历史排程与工时事实永不删改。
- **工时按版本管理**：修复师每次填报形成一个新版本（`t_repair_timesheet`，
  工单内版本号递增，`uk(project_id, version_no)`），明细行（`t_repair_timesheet_line`）
  固化起止时间/工序/设备，起止可跨天且原样落库。同一工单同时只允许一个待审核版本。
- **负责人审核**：`SUBMITTED → APPROVED / RETURNED`；退回必填意见，退回/通过后版本
  **封存只读**，任何补充只能另开新版本（与会签版本同一口径）。
- **设备互斥**：`SUBMITTED/APPROVED` 版本的明细行参与占用；提交时按设备 id 升序加行锁
  串行化并发，锁内复查——同一设备时段重叠且属于**另一个修复中工单**即拒绝；
  退回版本的行不再占用设备。
- **跨天拆分统计**：原始行不拆库，后台统计按自然日把每行拆到每天，
  按（自然日+工单+设备）汇总工时分钟数。
- **设备故障改期**：故障登记（`t_repair_equipment_fault`，单号 `FT-000001` 序列取号）后
  设备置 `FAULT`（故障中不可填报新工时），并为受影响行（修复中工单的有效行、
  与故障窗口重叠）自动生成改期建议（`t_repair_reschedule_suggestion`，
  `uk(fault_id, timesheet_line_id)` 幂等）：建议起点=预计恢复时刻、时长与原行一致。
  **建议只追加，原排程（工时明细行）绝不被悄悄改写**；采纳/忽略只改建议状态留痕，
  实际改期只能走工时单新版本。故障恢复后设备回到 `ACTIVE`。
- **同口径查询**：页面列表 `GET /api/repair-schedule`、CSV 导出
  `GET /api/repair-schedule/export`、后台按日统计 `GET /api/repair-schedule/summary`
  三端共用同一查询方法与过滤参数。文物 `REVOKED`（置为无效）后其行默认不计入；
  `includeRevoked=true` 时三端同样保留并以 `relicStatus=REVOKED` 标明。
- 工时提交/审核通过/审核退回/改期建议生成全部只追加写入 `t_relic_ledger`。

### 工时与排程接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/repair-equipment` / `GET /api/repair-equipment` / `GET /api/repair-equipment/{id}` | 设备建档（编号唯一）/ 列表 / 详情（含故障单与改期建议） |
| `POST /api/repair-projects/{id}/timesheets` | 填报工时（起止/工序/设备；设备同时段不被两个工单占用） |
| `GET  /api/repair-projects/{id}/timesheets` | 工单的工时单版本列表 |
| `GET  /api/timesheets/{id}` | 工时单详情（版本+明细行） |
| `POST /api/timesheets/{id}/review` | 负责人审核：APPROVE 通过 / RETURN 退回（退回必填意见，封存只读） |
| `POST /api/repair-equipment/{id}/faults` | 故障登记：设备置故障中，自动生成改期建议（原排程不变） |
| `POST /api/repair-equipment/{id}/faults/{faultId}/close` | 故障恢复：关闭故障单，设备回到在用 |
| `GET  /api/repair-suggestions?equipmentId=&projectId=&status=` | 改期建议列表 |
| `POST /api/repair-suggestions/{id}/adopt` / `POST /api/repair-suggestions/{id}/dismiss` | 采纳/忽略建议（只留痕，不改写原排程） |
| `GET  /api/repair-schedule?projectId=&equipmentId=&from=&to=&includeRevoked=` | 页面查询：有效工时行与设备占用排程 |
| `GET  /api/repair-schedule/export`（同参数） | CSV 导出：与页面同一行集同一顺序 |
| `GET  /api/repair-schedule/summary`（同参数） | 后台统计：同一行集按自然日拆分汇总 |

### 测试

`RepairTimesheetScheduleTest`（独立内存库）覆盖：设备编号唯一；未开工工单不能填报；
起止时间校验；同一工单同时仅一个待审核版本；设备同时段跨工单占用拒绝、紧邻不重叠允许；
退回必填意见、封存只读、退回后设备占用释放、补充只能另开新版本（版本号递增）；
排程页面只含有效版本行；跨天作业按自然日拆分（4h/24h/9h）与总量一致；
设备故障自动生成改期建议（建议起点=预计恢复时刻）、故障中不可填报、
原排程不被故障/采纳悄悄改写、故障恢复设备回到在用；
文物置为无效（REVOKED）后页面/导出/后台统计默认同口径剔除、
`includeRevoked=true` 三端同样保留并标明 REVOKED、页面行数=导出行数、页面工时总量=统计总量；
台账全动作留痕（TS_SUBMIT/TS_RETURN/TS_APPROVE/RESCHEDULE_SUGGEST）。

## 文物借阅审批（museum-10）

文物按**保护等级**配置**可借阅场景、最高借阅时长、审批角色**；提交借阅申请时
**校验当前展陈计划（展出中须先撤展）与修复状态（立项/修复中不可借）**；
**一级高风险文物必须在常规审批链后追加馆长本人签名**；审批全部通过后生成
**不可变借阅快照**；**归还延期必须重新走完整审批链**；**审批人不能查看不属于
自己馆区的完整保险资料**（跨馆区保险金额脱敏，不返回任何数字）。修复验收
**附条件通过（部分成功）**时，保护等级补偿与借阅补偿**成对开立、互持 id、
联动闭环**。

### 借阅审批规则

- **按等级的借阅策略**（`t_borrow_grade_policy`，一等级一行当前值）：可借阅场景、
  最高时长（自然日）、有序审批角色（馆藏部 `BORROW_DEPT` / 馆藏主管
  `COLLECTION_LEADER` / 馆长 `DIRECTOR`）、高风险是否追加馆长签名。
  默认：一级限馆外展览/学术交流、30 天、双签后**必须馆长签名**；二级 60 天双签；
  三级 90 天单签。策略调整只影响**之后提交**的申请——申请提交时把等级、馆区、
  角色链、馆长要求、场景、时长、保险金额全部固化（快照口径与规则版本一致）。
- **申请校验**：文物在档；无在途旧借出/未闭环借展协议/未闭环借阅申请；
  不在当前展陈计划（`t_hall_relic=ON_DISPLAY`，须先撤展）；无立项/修复中项目；
  场景必须在等级策略允许范围内；时长（按计划出库到预计归还的自然日差）不得超上限。
- **顺序审批**（`t_borrow_approval_record`，只追加，`uk(application_id, extension_id, step_no)`）：
  按固化角色顺序逐人签署，错序/越级拒绝；驳回必填意见且任一驳回即终止
  （申请置 `REJECTED`，无快照）；高风险链在常规角色后追加一步馆长角色签名，
  该步 `director_sign=1`，全部通过才 `APPROVED`。
- **借阅快照**（`t_borrow_snapshot`，一件申请一份，不可变）：固化文物档案、
  借用方、场景/期限/保险、完整审批链文本与馆长签名人。
- **出库与归还**：`APPROVED → BORROWED` 出库前复查展陈/修复/提前收回补偿，
  贯通写一条 `t_relic_loan(LENT)`，继续复用 museum-1 跨业务占用校验；
  `BORROWED → RETURNED` 在原单闭环并同步归还贯通借出单。
- **归还延期重新审批**（`t_borrow_extension`，申请内序号递增）：只有在借可申请，
  新期限必须晚于当前应还，同一申请同时只允许一条重审中延期；延期重走**完整**
  审批链（高风险仍须馆长签名），通过才回写申请与贯通借出单的应还时间，
  驳回/撤销**不改原应还时间**；借阅归还时审批中的延期单自动驳回。
- **保险资料馆区隔离**：文物带 `campus_code`（建档指定，默认主馆区 MAIN）。
  申请列表/详情/快照/借阅补偿查询均带 `viewerCampus`：同馆区返回完整保险金额
  （`insuranceViewable=true`），非同馆区或未声明馆区时保险金额置空、
  `insuranceViewable=false`，不返回任何保险数字。

### 部分成功补偿规则

- 修复验收 **CONDITIONAL（附条件通过＝部分成功）必须登记验收后保护等级**：
  验收即把等级调整落到文物档案，并开立一条保护等级补偿
  （`t_repair_grade_compensation`，一项目一条，固化等级前/后，单号 `GC-000001`）。
- **与借阅补偿一一对应**（`t_borrow_compensation`，单号 `BC-000001`，两表互持 id）：
  - 在借/已批准待出库借阅 → `RECALL` 提前收回：补偿未闭环**禁止出库**，
    文物收回（归还或撤销借阅）后借阅补偿方可闭环；
  - 无在途但有最近一次**已归还**借阅 → `SUPPLEMENT_CONFIRM` 等级与保险差异补充确认
    （可登记确认后保险金额）；
  - 审批中的借阅申请按新等级口径**直接驳回**（策略快照失效，须重新申请），
    不形成补偿单；无任何借阅时只开立等级补偿。
- **联动闭环顺序**：成对借阅补偿必须先闭环，保护等级补偿才允许确认；
  确认时可给出最终等级（再次更正文物档案），缺省按验收调整后等级确认。
- 借阅归还时未闭环的借阅补偿随归还自动闭环留痕；补偿开立/闭环、
  借阅驳回全部只追加写入 `t_relic_ledger`（GRADE_COMP_OPEN/BORROW_COMP_OPEN/
  GRADE_COMP_RESOLVE/BORROW_COMP_RESOLVE 等）。

### 借阅与补偿接口

| 方法与路径 | 作用 |
| --- | --- |
| `GET /api/borrow-policies` / `PUT /api/borrow-policies/{grade}` | 查看/调整各保护等级借阅策略当前值（只影响新申请） |
| `POST /api/relics/{id}/borrow-applications` | 提交借阅申请（校验展陈计划/修复状态/场景/时长，固化策略快照） |
| `GET /api/borrow-applications?relicId=&status=&viewerCampus=` | 申请列表（保险金额按馆区脱敏） |
| `GET /api/borrow-applications/{id}?viewerCampus=` | 申请详情：审批链/借阅快照/历次延期重审链（保险脱敏） |
| `POST /api/borrow-applications/{id}/approvals` | 原申请链顺序审批（高风险末步馆长签名；驳回必填意见） |
| `POST /api/borrow-applications/{id}/lend` | 批准后出库（复查展陈/修复，RECALL 未闭环禁出），贯通写借出记录 |
| `POST /api/borrow-applications/{id}/return` | 归还闭环（审批中延期自动驳回，RECALL 补偿随归还闭环） |
| `POST /api/borrow-applications/{id}/cancel` | 撤销申请（PENDING；有提前收回补偿时 APPROVED 未出库也可撤） |
| `POST /api/borrow-applications/{id}/extensions` | 归还延期申请（在借、晚于当前应还，重新走完整审批链） |
| `POST /api/borrow-extensions/{extensionId}/approvals` | 延期重审链签署（高风险仍须馆长签名） |
| `GET/POST /api/repair-grade-compensations[/{id}/resolve]` | 等级补偿列表/详情/闭环确认（成对借阅补偿先闭环） |
| `GET/POST /api/borrow-compensations[/{id}/resolve]?viewerCampus=` | 借阅补偿列表/详情（保险按馆区脱敏）/RECALL 或补充确认闭环 |
| `POST /api/repair-projects/{id}/accept` | 验收：`result=CONDITIONAL` 时必带 `gradeAfter`，触发成对补偿 |

### 测试

`BorrowApprovalTest`、`BorrowHighRiskExtensionTest`、`RepairPartialCompensationTest`
（各自独立内存库）覆盖：等级策略场景/最高时长校验与策略只影响新申请；
展出中/立项修复中申请拦截、撤展与项目作废后放行、出库时点再校验；
二三级常规角色链错序/越级/驳回必填意见；一级三级审批链、馆长追加签名
（`director_sign`/快照馆长签名人）、主管驳回终止无快照；
批准出库贯通 `t_relic_loan`、归还闭环；归还延期完整重审（含馆长）、
驳回不改应还时间、通过回写申请与借出单、归还自动驳回在审延期、非在借不可延期；
保险金额同馆区可见/跨馆区与未声明馆区脱敏（申请、快照、借阅补偿）；
CONDITIONAL 必带合法等级、无借阅/已批准待出库 RECALL/已归还 SUPPLEMENT_CONFIRM/
审批中驳回四种补偿对应、两补偿互持 id、RECALL 阻断出库且先闭环、
等级补偿不得先于借阅补偿闭环、确认时最终等级再更正；全动作台账留痕。

## 文物鉴定意见与修复操作并发（museum-11）

文物**鉴定一案串联初鉴与复鉴**，两轮意见**分别固化专家、依据、置信说明与附件**，
任何一轮都不覆盖另一轮；两轮已发布结论**冲突时进入仲裁**，绝不用最后一条意见覆盖前文。
意见**未发布可由专家本人撤回**（留痕不删档），**已发布只能追加更正、原文永不改写**。
**普通馆员只能看到结论摘要**，**下载详细报告逐条记录审计流水**（放行/拒绝都留痕）。
博物馆修复的**同一操作并发发起**时，项目行锁串行化，**谁生效、谁被拒绝逐条落审计**。

### 鉴定意见规则

- **案件头**（`t_appraisal_case`，单号 `AP-000001`）：`OPEN 待初鉴 → REVIEWING 待复鉴
  → CONCLUDED 已定稿`；结论冲突时旁路进入 `ARBITRATING 仲裁中`，仲裁定稿后才
  `CONCLUDED`。案件头快照最终结论落在第几环（`INITIAL/REVIEW/ARBITRATION`）、
  最终意见 id、定稿人与对外结论摘要。
- **两轮意见分别成行**（`t_appraisal_opinion`，`uk(case_id, stage, round_no)`）：
  初鉴 `INITIAL`、复鉴 `REVIEW`，各自独立保存专家姓名/职称、**鉴定依据**、
  **置信说明**（把握程度、不确定性、边界条件）、结论码（真 `AUTHENTIC` / 伪
  `COUNTERFEIT` / 部分真 `PARTIAL` / 暂不定论 `INCONCLUSIVE`）、结论文本；
  附件统一登记 `t_appraisal_attachment`（意见/更正/仲裁三选一归属，CHECK 约束保证
  仅一处非空）。
- **未发布草稿可撤回**：意见先存 `DRAFT`，只能由**专家本人**撤回，置 `WITHDRAWN`
  并填撤回原因——**留痕不删行、不删附件**；撤回后重提即该轮次下一 `round_no`。
  他人/管理员不能代撤代发。
- **已发布只能追加更正**：`PUBLISHED` 意见原文（依据/置信说明/结论）**永不改写、
  不可撤回**；修订只能由原专家本人追加 `t_appraisal_correction`（意见内序号递增），
  原文与全部更正共同构成该专家完整立场。
- **冲突进仲裁，不覆盖**：复鉴发布时与已发布初鉴比对——结论一致直接定稿；
  双方结论码不同且都不是 `INCONCLUSIVE`（暂不定论不构成对立）时，自动开立仲裁单
  `t_appraisal_arbitration`（单号 `ARB-000001`，固化双方意见 id 与冲突摘要），
  案件进入 `ARBITRATING`，**初鉴/复鉴同时冻结，不能再提交意见覆盖**。仲裁人可
  `ADOPT_INITIAL 采初鉴` / `ADOPT_REVIEW 采复鉴`（结论自对应意见带入，对方原文保留）
  / `ADJUSTED 综合调整`（必须显式给出最终结论码、文本、依据、置信说明）。
  最终结论只落在案件头与仲裁单上。
- **权限分层与报告下载审计**：普通馆员 `LIBRARIAN` 只能读 `conclusion_summary`
  （无专家、依据、置信说明、附件）；鉴定专家 `APPRAISAL_EXPERT`、仲裁人 `ARBITER`
  可取全量详细报告。每次下载（**含被权限拒绝**）都在独立事务写一条
  `t_appraisal_report_audit`（操作人/角色/放行 GRANTED 或拒绝 DENIED/原因/IP/时间），
  并落 `APPRAISAL_REPORT_DL` / `APPRAISAL_REPORT_DENY` 台账——拒绝流水独立事务，
  不随主业务回滚丢失。

### 修复同一操作并发规则

- **五类操作**（开工 `START` / 阶段登记 `STAGE` / 修复完成 `FINISH` / 验收 `ACCEPT`
  / 作废 `CANCEL`）统一走并发安全入口 `RepairOperationService`，原有业务规则
  （会签通过才开工、外借占用、阶段数、验收补偿等）全部保留。
- **项目行锁串行化**：进入操作先 `UPDATE t_repair_project … WHERE id=?` 取项目行排他锁
  （锁持有到事务提交，不用 `SELECT … FOR UPDATE`），同一项目的并发请求排队，
  先提交者生效，后者锁内复查到已变化的状态后被拒绝。
- **谁生效、谁被拒绝逐条审计**（`t_repair_operation_attempt`）：
  - 生效的一次与项目状态推进**同一事务原子提交**，记 `EFFECTIVE` 及产出业务行 id；
  - 落败/状态不允许的一次业务事务**整体回滚**（阶段/台账等业务写入全部撤销），
    在 `REQUIRES_NEW` 独立事务补写一条 `REJECTED`，含拒绝原因并指向
    **最终生效尝试 id**，同时落 `REPAIR_OP_REJECT` 台账（摘要写明“最终生效为尝试#N”）。
    因此“谁生效、谁被拒绝、为什么”都可追溯，且审计不随回滚丢失。
  - 可重复操作（如阶段登记）并发时每次都生效，行锁保证阶段号 `1..N` 不重不丢；
    状态跃迁操作（完成/验收等）并发时恰一个 `EFFECTIVE`，其余全部 `REJECTED`。
- **请求流水号幂等**：`request_no` 全表稀疏唯一。修复侧补传/补录/重试复用同一流水号，
  锁前快查、锁内二次回读，命中同一次尝试即回放原结果（生效回放业务结果，被拒回放
  原拒绝原因），绝不二次执行、绝不产生第二条尝试。

### 鉴定与并发接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/appraisal-cases` | 开立鉴定案件（关联文物，可带修复项目） |
| `POST /api/appraisal-cases/{id}/opinions` | 提交初鉴/复鉴意见（`publish=true` 直接发布，否则存可撤回草稿） |
| `POST /api/appraisal-cases/opinions/{opinionId}/publish` | 专家本人发布未发布草稿 |
| `POST /api/appraisal-cases/opinions/{opinionId}/withdraw` | 专家本人撤回未发布意见（必填原因，留痕不删档） |
| `POST /api/appraisal-cases/opinions/{opinionId}/corrections` | 已发布意见追加更正（原专家本人，原文不改写） |
| `POST /api/appraisal-cases/arbitrations/{arbitrationId}/conclude` | 仲裁定稿：采初鉴/采复鉴/综合调整 |
| `GET /api/appraisal-cases/{id}/detail` | 专家/仲裁全量详情：两轮意见+更正+附件+仲裁单 |
| `GET /api/appraisal-cases/{id}/summary` | 普通馆员视角：仅结论摘要 |
| `POST /api/appraisal-cases/{id}/report-download?operatorName=&role=` | 下载详细报告（专家/仲裁放行，馆员拒绝；均写审计） |
| `GET /api/appraisal-cases/{id}/report-audits` | 报告下载审计流水（放行/拒绝） |
| `POST /api/repair-projects/{id}/start|finish` | 并发安全开工/完成（body 可选 `requestNo`、`operatorName`） |
| `POST /api/repair-projects/{id}/stages|accept|cancel` | 并发安全阶段登记/验收/作废（body 可选 `requestNo`） |
| `GET /api/repair-projects/{id}/operation-attempts?operation=` | 并发尝试审计：谁 EFFECTIVE、谁 REJECTED 及胜出尝试 |

### 测试

`AppraisalOpinionTest`、`RepairOperationConcurrentTest`（各自独立内存库）覆盖：
草稿非本人不可撤/撤回必填原因/撤回留痕不删档/撤回后不可再发布、已发布不可撤回、
更正只能本人且追加不改原文；初鉴未发布不能复鉴、复鉴与初鉴一致直接定稿不仲裁、
真/伪冲突进仲裁且两轮原文都保留、仲裁期间意见冻结、非指定仲裁人不可定稿、
采初鉴/综合调整（缺结论要素拒绝）、暂不定论不构成冲突；普通馆员摘要无专家/依据/
附件、详细报告馆员被拒写 DENIED、专家放行写 GRANTED，两条审计都在且台账留痕；
8 并发阶段登记全部生效阶段号 1..8、8 并发修复完成恰 1 生效 7 拒绝且被拒记录指向
同一胜出尝试、7 条 `REPAIR_OP_REJECT` 台账均写明最终生效尝试、胜出流水号重放不二次执行、
8 并发同一流水号开工只形成一条生效尝试。

## 展览借品清点盘点（museum-12）

按**展厅**生成清点任务，工作人员扫描**箱号与展签**，系统列出**缺失、错位和状态异常**；
**差异单**引用**现场照片与见证人**且差异必须能解释，**复核完成前不能关闭展览**；
清点期间发生**借还变更提示版本冲突**，重基线**用旧数据回放后提交新版本**；
**已关闭任务只能新增补充记录，不能重写原结果**。

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_inv_task` | 清点任务：按展厅生成一轮一行，`open_key` 稀疏唯一保证一展厅同时在途任务唯一；固化基线版本与基线事件位点；关闭时固化相符/缺失/错位/状态异常汇总 |
| `t_inv_task_line` | 基线快照行：按版本只追加，展签/箱号/点位/在借单 id 快照，旧版本永不改写 |
| `t_inv_scan` | 扫描记录：只追加；`MATCHED/MISPLACED/ABNORMAL/UNEXPECTED`；`(task_id, baseline_version, display_no)` 唯一；回放行带 `replayed_from_id` 溯源 |
| `t_inv_diff_order` / `t_inv_diff_line` | 差异单（一任务一版本一单，说明+见证人必填）与逐行差异（缺失/错位/状态异常，期望与实际固化） |
| `t_inv_diff_photo` | 差异单现场照片：SHA-256 内容寻址，复核前至少一张 |
| `t_inv_supplement` | 补充记录：已关闭任务唯一允许的新增 |
| `t_inv_ledger` | 清点台账：任务生成/扫描/重基线/差异单/复核/关闭/补充/展厅关闭只追加留痕 |
| `t_hall_relic.box_no` | 在展文物箱号（上展登记，清点基线按此快照） |

### 清点规则

- **按展厅生成任务**：`POST /api/inv-tasks` 一展厅同时只有一个 `OPEN` 任务（`open_key`
  稀疏唯一），重复生成幂等命中同一任务；`(hall_id, round_no)` 唯一，轮次递增。
- **扫描判定**：展签定位基线行——箱号/点位不符判**错位**；文物已撤销或在借未还却在场
  判**状态异常**；展签箱号都不在基线判**未在基线**（归入状态异常）；基线在册未扫描判**缺失**；
  同版本同展签重复扫描拒绝。
- **版本冲突**：清点期间发生借还变更（文物在借单与基线快照不一致，借出/归还都算）
  或展陈变更（在展集合变化）时，扫描/开立差异单/关闭任务一律拒绝并提示版本冲突；
  `POST /api/inv-tasks/{id}/rebaseline` 生成新基线版本（版本号递增），旧版本扫描
  **逐条回放**进新版本并按新基线重算判定（`replayed_from_id` 溯源），旧版本数据原样保留。
- **差异必须能解释**：差异单说明与见证人必填（两名见证人不得同名），现场照片内容寻址、
  复核前至少一张；差异逐行固化期望与实际。复核退回须填意见；复核通过才 `REVIEWED`。
- **复核完成前不能关闭展览**：`POST /api/halls/{id}/close` 在展厅有清点中任务或
  未复核差异单时一律拒绝；任务关闭要求全部差异单复核完成、当前版本差异均已开立差异单。
- **已关闭任务只能新增补充记录**：关闭后扫描/重基线/开立差异单均拒绝，
  `POST /api/inv-tasks/{id}/supplements` 只追加补充记录，原扫描与差异结果永不重写。

### 清点接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/inv-tasks` / `GET /api/inv-tasks?hallId=&status=` | 按展厅生成清点任务（幂等）/ 任务列表 |
| `GET  /api/inv-tasks/{id}` | 任务详情：任务头 + 当前版本基线行 + 扫描 + 差异单 + 补充记录 |
| `POST /api/inv-tasks/{id}/scans` | 扫描箱号与展签（借还/展陈变更后提示版本冲突） |
| `GET  /api/inv-tasks/{id}/diff-report` | 差异清单：缺失 / 错位 / 状态异常 |
| `POST /api/inv-tasks/{id}/rebaseline` | 重基线：旧数据回放后提交新版本 |
| `POST /api/inv-tasks/{id}/close` | 关闭任务（差异全部复核完成；汇总固化） |
| `POST /api/inv-tasks/{id}/supplements` | 补充记录（已关闭任务唯一允许的新增） |
| `POST /api/inv-tasks/{id}/diff-orders` | 开立差异单（说明+见证人必填，差异必须能解释） |
| `GET  /api/inv-diff-orders?taskId=&status=` / `GET /api/inv-diff-orders/{id}` | 差异单列表 / 详情（逐行差异+现场照片） |
| `POST /api/inv-diff-orders/{id}/photos` | multipart 上传现场照片（内容寻址去重，复核前至少一张） |
| `POST /api/inv-diff-orders/{id}/review` | 复核：通过 / 退回（退回必填意见） |
| `POST /api/halls/{id}/close` | 关闭展厅（展览）：复核完成前不能关闭展览 |
| `GET  /api/inv-ledgers?hallId=` | 清点台账（不传展厅为全馆） |

### 测试

`ExhibitionInventoryTest`（独立内存库）覆盖：按展厅生成幂等、重复扫描拒绝；
箱号不符判错位、已撤销/在借在场判状态异常、未在基线判异常、基线在册未扫描判缺失；
差异单说明/见证人必填、两名见证人不得同名、复核前必须有现场照片、同内容照片去重、
退回必填意见；复核完成前展厅不能关闭、复核通过后任务关闭汇总固化；
已关闭任务扫描/重基线/差异单均拒绝、只能新增补充记录且原结果不变；
借出与归还变更、撤展变更均提示版本冲突；重基线版本递增、旧扫描逐条回放可溯源、
归还后回放重算恢复相符；台账全动作留痕且按展厅隔离。

## 文物运输包装规格（museum-13）

文物运输包装规格按**版本**管理：条目按**材质、尺寸档（适用外形区间）、缓冲等级
（防震）与气候区**配置；装箱时校验文物**重量、外形和防震要求**；**规格变更不影响
已签押运单**；**临时替代包装必须审批并记录差异**；**装箱照片与箱号绑定**；
**拆箱后包装状态要回写但不能覆盖发运前证据**；博物馆文物修复**外部依赖无响应时
不假装成功，恢复后也不重复通知**。

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_pack_spec_version` / `t_pack_spec_item` | 包装规格版本与条目（材质+尺寸档+缓冲等级+气候区唯一，外形区间/承重/防震限值随版本固化），发布新版本旧版本置 `SUPERSEDED` 只追加 |
| `t_pack_order` | 装箱单：关联已签押运单（借展协议），箱号全库唯一；装箱时把规格版本与命中条目**全量快照**进单头（spec_* 列），规格之后变更不影响本单 |
| `t_pack_substitute` | 临时替代包装审批单（一单一条）：固化替代包装实际能力与对标准规格的缓冲/防震/承重/外形/气候**逐项差异**；`PENDING/APPROVED/REJECTED` |
| `t_pack_photo` | 装箱照片：与箱号绑定，分 `BEFORE_SHIP 发运前` / `AFTER_UNPACK 拆箱后` 两阶段，SHA-256 内容寻址去重，两阶段分别只追加 |
| `t_pack_notify_outbox` | 外部修复依赖装箱确认通知发件箱：`PENDING → SENDING → SENT`，一单一条，行级条件 UPDATE 认领 |
| `t_pack_ledger` | 包装台账：规格发布/装箱/替代审批/外部通知/拆箱只追加留痕，标明规格版本 |

### 装箱规则

- **规格版本化**：`POST /api/pack-specs/publish` 发布新版本后旧生效版本置
  `SUPERSEDED`（内容永不改写）。装箱单固化 `spec_version_id/no` 与命中条目全列快照——
  **规格变更（新版本）不影响已签押运关联装箱单**，历史装箱仍按 v1 承重/防震/外形核验。
- **装箱三校验**：按 材质 + 运输气候区 + 实测外形区间 在生效版本中定位条目，再校验
  ① 重量（实测总重 ≤ 条目承重上限）、② 外形（长/宽/厚同时落入适用区间）、
  ③ 防震（条目缓冲等级不弱于本次防震要求；缓冲等级 `L1>L2>L3`，防震要求缺省按
  文物保存等级：一级 L1、二级 L2、三级 L3，也可显式传 `requiredCushionLevel`）。
  任一项不满足一律拒绝。
- **押运单约束**：只能对**已签署/押运中**的借展协议装箱，押运单须属于该文物；
  同一押运单同一文物只允许一张有效（非 `REJECTED`）装箱单；箱号全库唯一。
- **请求流水号幂等**：`requestNo` 全库唯一，补传/补录/重试命中同一装箱单原样回放，
  绝不二次执行、不重复通知。
- **临时替代包装**：登记替代原因、申请人与替代包装实际能力；系统生成并固化与标准
  规格的逐项差异。装箱单先置 `PENDING_APPROVAL`，**必须审批**——批准后才进入外部
  确认与发运，驳回必填意见且装箱单终态 `REJECTED`；被拒后可就同一押运单重新申请。
  替代包装自身仍须过重量/外形/防震/气候校验，能力不足直接拒绝。
- **照片绑箱号**：发运前照片只能在装箱后拆箱前传，拆箱后照片只能在拆箱回写后传，
  每张照片冗余箱号快照、内容寻址同箱同阶段去重。
- **拆箱回写不覆盖证据**：仅 `PACKED`（外部已确认）可拆，且拆箱前必须已有 ≥1 张
  发运前照片；受损 `DAMAGED` 必填说明。回写只写 `unpack_*` 列并另存 `AFTER_UNPACK`
  照片，规格快照、实测值与 `BEFORE_SHIP` 照片等发运前证据永不被覆盖。

### 外部修复依赖通知（不假装成功、恢复不重复通知）

- 装箱落库与发件箱记录在事务内提交后再通知外部依赖；网关
  （`MuseumRepairClient`，地址 `museum.packaging.repair-notify-url`，未配置按不可达）
  无响应/超时一律抛 `MuseumRepairUnavailableException`——**装箱单停留
  `NOTIFY_PENDING`，接口返回 `notified=false` 与原因，绝不假装成功**。
- 投递状态机 `PENDING → SENDING → SENT`，以条件 UPDATE（`WHERE status='PENDING'`）
  **行级认领**：手动重试、定时补偿（启动补投 + `notify-scan-interval-ms` 周期扫描，
  串行不重入）、重启恢复相互串行；`SENT` 永不重投，崩溃停在 `SENDING` 的行先复位再投。
  因此**恢复后同一装箱单恰好通知一次，不会重复通知**；载荷带幂等键
  `notifyKey=PACK-<装箱单号>`，外部也可据此去重。

### 包装接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/pack-specs/publish` | 发布包装规格新版本（材质/尺寸/缓冲/气候区条目，旧版自动 SUPERSEDED） |
| `GET /api/pack-specs` / `GET /api/pack-specs/active` / `GET /api/pack-specs/{id}` | 版本列表 / 当前生效版本 / 版本条目详情 |
| `POST /api/pack-orders` | 按规格装箱（`requestNo` 幂等；校验重量/外形/防震，固化规格快照，外部确认无响应则停留待确认） |
| `POST /api/pack-orders/substitute` | 临时替代包装申请（逐项记录差异，待审批） |
| `POST /api/pack-substitutes/{id}/approve` | 替代包装审批：批准进入外部确认 / 驳回（必填意见）装箱单 REJECTED |
| `GET /api/pack-orders` | 装箱单列表（可按文物/押运单/状态/箱号过滤） |
| `GET /api/pack-orders/{id}` | 装箱单详情：规格快照 + 替代审批 + 发运前/拆箱后照片 + 外部通知状态 |
| `POST /api/pack-orders/{id}/photos` | multipart 上传装箱照片（`stage=BEFORE_SHIP/AFTER_UNPACK`，与箱号绑定，内容寻址去重） |
| `POST /api/pack-orders/{id}/unpack` | 拆箱包装状态回写（INTACT/DAMAGED，受损必填说明；不覆盖发运前证据） |
| `POST /api/pack-orders/{id}/notify-retry` | 手动补偿投递外部通知（恢复后行级认领，只通知一次） |
| `GET /api/pack-ledgers?relicId=&orderId=` | 包装台账（只追加，不传文物为全馆） |

### 测试

`PackagingSpecTest`（独立内存库，外部依赖用可切换"无响应/恢复"的测试桩）覆盖：
规格版本化发布、旧版 SUPERSEDED；重量超限/外形超区间/防震不足/未签押运单均拒绝；
外部无响应时装箱单停留 `NOTIFY_PENDING`、接口不假装成功、流水号重试只形成一条；
发布 v2 后已装箱单仍固化 v1 快照（承重/防震/外形不变）；恢复后补偿**恰好通知一次**、
重复定时/手动触发不再重投；照片与箱号绑定、两阶段时序与去重、拆箱只另存不覆盖；
临时替代能力不足拒绝、差异逐项固化、驳回（缺意见拒绝）装箱单 `REJECTED`、被拒后
重新申请批准才可发运、受损拆箱必填说明；包装台账全动作留痕。

## 修复成果验收与开放标记（museum-14）

修复成果验收独立于项目验收闭环（museum-1 的 `/accept`）：修复师提交**成果报告**后，
系统按**文物材质 + 修复工艺**从配置模板（`museum.acceptance.checklist`，
精确匹配 → `材质+*` → `*+*` 逐级回落）生成**验收清单**；**馆内专家与保管员分别
逐项确认**（须指定确认人本人，不能代签，每角色一次），**任一项缺项即进入整改**，
整改后重新提交新一轮报告（轮次项目内递增，旧轮次只读保留）；双方全部合格则
**验收通过（PASSED，终态）**。**开放展示标记只能在验收通过后设置**，登记对外摘要
与授权影像子集；**公众页面只展示生效标记中已授权的影像和摘要**（不暴露摄影者、
设置人等内部字段）；开放后发现问题**开立补修单，原开放记录保留**；验收详情、
台账列表、CSV 导出与台账流水同一口径，可交叉核对。

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_repair_accept_report` | 成果报告：轮次（项目内唯一递增）、工艺、摘要、指定专家/保管员、提交人；状态 `IN_REVIEW / RECTIFICATION / PASSED` |
| `t_repair_accept_item` | 验收清单项：提交时按材质+工艺模板固化（`uk(report_id,item_no)`），`expert_result` / `custodian_result` 分别记录双方逐项确认（OK/MISSING） |
| `t_open_display_grant` | 开放展示标记：关联通过轮报告，登记对外摘要；`ACTIVE / REVOKED`（撤销留痕，记录保留） |
| `t_open_display_image` | 授权影像子集（`uk(grant_id,image_id)`）：公众页面只展示此子集 |
| `t_repair_supplement` | 补修单：单号序列取号（`SP-%06d`），关联开放标记；`OPEN / CLOSED`，开立不触碰原开放记录 |

### 验收与开放规则

- **清单按材质+工艺生成**：提交报告时以文物材质 + 报告工艺查配置模板并**固化为清单行**，
  之后配置变更不改写已生成清单；未命中精确模板回落 `材质+*`，再回落 `*+*` 默认模板。
- **双人分别确认**：角色 `INTERNAL`（馆内专家）/ `CUSTODIAN`（保管员），确认人须为报告
  指定确认人本人（缺席不能代签）；每角色只确认一次；逐项结果必须恰好覆盖全部清单项。
- **缺一项即整改**：任一角色任一项 `MISSING`，报告立即 `RECTIFICATION` 并只读，
  不等另一角色；整改只能**重新提交新一轮报告**（旧轮次保留可查）。双方全部 `OK`
  才 `PASSED`；`PASSED` 后不再接受新报告。
- **开放标记门槛**：仅当项目存在 `PASSED` 报告才能设置开放展示标记；同一项目同时
  只允许一个生效标记；授权影像须属于该文物且有效（作废影像自动从展示中剔除，
  授权记录本身保留）。撤销只置 `REVOKED`，记录保留。
- **公众只读授权内容**：`/api/public/open-displays` 只返回生效标记的对外摘要与授权
  影像（视角/版本/拍摄时间），不暴露摄影者、设置人等内部字段；未开放/已撤销文物
  对公众不可见。
- **补修单保留原开放记录**：须有生效开放标记才能开立补修单；开立/闭环只追加台账，
  原开放记录（含授权影像）保持原样；闭环登记处置结果，记录保留。
- **交叉核对**：报告详情、台账列表（`GET /api/accept-reports`）、CSV 导出
  （`GET /api/accept-reports/export`，与列表同一行集同一顺序，带 BOM）与台账流水
  （`ACCEPT_REPORT_SUBMIT / ACCEPT_CONFIRM / ACCEPT_RECTIFY / ACCEPT_PASS /
  OPEN_DISPLAY_SET / OPEN_DISPLAY_REVOKE / SUPPLEMENT_OPEN / SUPPLEMENT_CLOSE`）
  四处口径一致。

### 成果验收与开放接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/repair-projects/{id}/accept-reports` | 修复师提交成果报告（生成验收清单，轮次递增） |
| `GET /api/repair-projects/{id}/accept-reports` | 项目报告轮次列表（含各轮清单与双方确认结果） |
| `POST /api/accept-reports/{reportId}/confirm` | 专家/保管员逐项确认（本人确认，缺项即整改） |
| `GET /api/accept-reports/{reportId}` | 报告详情：报告 + 清单逐项结果 + 项目/文物口径 |
| `GET /api/accept-reports?relicId=&status=` | 验收台账列表（与导出行集一致） |
| `GET /api/accept-reports/export` | CSV 导出（与列表同一行集同一顺序） |
| `POST /api/repair-projects/{id}/open-display` | 设置开放展示标记（须验收通过；登记摘要+授权影像） |
| `POST /api/open-displays/{grantId}/revoke` | 撤销开放标记（留痕，记录保留） |
| `GET /api/repair-projects/{id}/open-displays` | 项目开放记录（含已撤销） |
| `GET /api/public/open-displays` / `GET /api/public/open-displays/{relicId}` | 公众页面：仅生效标记的授权影像与摘要 |
| `POST /api/repair-projects/{id}/supplements` | 开立补修单（须生效开放标记，原记录保留） |
| `GET /api/repair-projects/{id}/supplements` | 项目补修单列表（含已闭环） |
| `POST /api/supplements/{id}/close` | 补修单闭环（登记处置结果） |

### 测试

`RepairAcceptanceOpenDisplayTest`（独立内存库）覆盖：清单按 材质|工艺 精确模板与
`材质|*` 回落生成；未修复完成/确认中重复提交/验收通过后提交均拒绝；角色非法、
代签、未覆盖全部清单项拒绝；专家判缺项即整改且报告只读；第二轮双方确认通过；
验收未通过不能设开放标记、跨文物影像拒绝、重复设置拒绝；公众页面只见授权影像与
摘要、不暴露内部字段、未开放不可见；补修单开立后原开放记录保留、公众仍可见、
闭环后不可重复闭环、撤销后不能再开立；**交叉核对**：详情/列表/CSV 导出行集一致，
台账动作序列完整且顺序正确（整改→通过→开放→补修）。

## 文物保险估值管理（museum-15）

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_ins_valuation_version` | 保险估值版本（文物内 `version_no` 递增）：来源（购藏/评估/拍卖/捐赠/其他）、来源说明、评估机构、金额、币种、有效期、凭证；状态 `DRAFT / PENDING_APPROVAL / ACTIVE / SUPERSEDED / REJECTED`，只追加流转、永不物理删除 |
| `t_insurance_endorsement` | 保险批单：估值版本双人审批通过生效时同事务出具，不可变；`INITIAL` 首次核保 / `ADJUST` 金额变更（固化新旧版本与金额），估值版本回填 `endorsement_id`，一版本至多一张批单 |
| `t_ins_valuation_snapshot` | 借展签约估值快照：按协议签署版本唯一、生成后不可变；固化来源/机构/金额/币种/有效期/凭证，签约时无生效估值也出快照行（金额留空并说明） |
| `t_ins_valuation_reminder` | 估值过期提醒：一版本一条，`OPEN / ACKNOWLEDGED`，定时扫描幂等生成；只提示，不改业务状态 |
| `t_permission_policy.valuation_change_open` | 单行策略新增开关：收紧后估值变更类动作在服务入口统一拒绝（与影像上传开关相互独立） |

### 估值登记与双人审批规则

- **必填要素**：来源、评估机构、金额（必须为正）、币种（CNY/USD/EUR/JPY/GBP/HKD）、
  有效期（止晚于起）。凭证编号/名称随版本登记，供财务核对。
- **金额修订必生新版本**：文物已有 `ACTIVE` 估值时再次登记即修订，必须填 `changeReason`；
  新版本独立走审批，审批完成前旧版本保持 `ACTIVE`；通过后旧版本同事务置 `SUPERSEDED`
  （金额与各字段原样保留不改写），新版本置 `ACTIVE`。
- **双人审批**：`FIRST` 初审 + `SECOND` 复核两步；两名审批人必须不同，且都不得是提交人本人
  （经办与审批分离）；初审不能重复、初审未到不能复核、同人复核拒绝。
- **两签齐备才生效**：第二签通过的同事务内完成旧版 `SUPERSEDED` → 新版 `ACTIVE` →
  出具保险批单（`ADJUST` 带旧版本金额，`INITIAL` 无上一版本）→ 回填 `endorsement_id`，
  全程台账留痕（提交/初审/复核/生效/批单各自一条）。
- **驳回留痕**：任一签 `REJECT`（必须填原因）版本置 `REJECTED` 永久保留、不能再审，
  修订只能重新登记新版本。

### 借展快照与过期提醒

- **签约即固化**：借展协议（museum-6）每次 `sign` 生效时自动出一份不可变估值快照；
  借展期间（押运/在展/归还）`valuation-view` 一律返回签约时快照——即使期间估值修订、
  出具批单或到期，在借金额/币种/来源口径都不变。协议变更重新签署则再出一份新快照，
  各按各的签署版本留档。
- **过期只提醒、不冻结归还**：调度器按 `museum.insurance.remind-scan-interval-ms`
  （默认 60s，启动补扫可关）扫描 `ACTIVE` 且已过 `effective_to` 的版本，每版本生成一条
  `OPEN` 提醒（幂等）；提醒不改变估值/借展/归还任何状态——在借文物估值过期时
  封箱/交接/到馆/开箱与归还验收照常完成。提醒由人工 `ACKNOWLEDGED` 关闭。

### 字段可见性与入口闸门

- **财务（FINANCE）**：当前估值、版本履历、借展快照视图都只投影金额、币种与凭证
  （`financeView=true`），来源/评估机构/有效期/修订原因/审批人/批单明细一律不下发。
- **公众（PUBLIC）/普通馆员（LIBRARIAN）**：触碰任何敏感估值入口（当前估值、版本履历、
  借展估值视图、快照列表）直接拒绝，不返回脱敏空壳；拒绝走独立事务写
  `VALUATION_ACCESS_DENY` 台账，放行与拒绝都可追溯。
- **缺权入口闸门**：`PUT /api/policy/valuation-change {"open":false}` 收紧后，
  博物馆文物修复用户对估值的一切推进型动作（登记/提交/初审/复核）在**服务层入口**
  抛 `PermissionClosedException` 拒绝；闸门不绑定具体 URL，换调用地址或内部直调同样拒绝，
  且不产生任何写入。已生效版本、批单、签约快照只读保留；只读查询与财务金额视图不受影响；
  `open=true` 放开后审批链可继续推进。收紧/放开各写全局台账 `VAL_PERMIT_TIGHTEN/RELAX`。

### 保险估值接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/ins-valuations` | 登记估值版本（默认 DRAFT；`submitNow=true` 直接进入双人审批；已有生效估值时修订原因必填） |
| `POST /api/ins-valuations/{id}/submit` | 草稿提交双人审批 |
| `POST /api/ins-valuations/{id}/approvals` | 双人审批签署（`step=FIRST/SECOND`，`decision=APPROVE/REJECT`；两人不同且非提交人） |
| `GET /api/relics/{relicId}/insurance-valuation?operatorName=&role=` | 当前生效估值视图（经办含批单；财务仅金额/凭证；公众/普通馆员拒绝） |
| `GET /api/relics/{relicId}/ins-valuation-versions?operatorName=&role=` | 估值版本履历（按角色投影） |
| `GET /api/loan-agreements/{id}/valuation-view?operatorName=&role=` | 借展期间估值口径：恒为签约时快照（角色投影同上） |
| `GET /api/loan-agreements/{id}/valuation-snapshots?operatorName=&role=` | 协议历次签署快照 |
| `GET /api/ins-valuation-reminders[?status=]` | 过期提醒列表 |
| `POST /api/ins-valuation-reminders/scan` | 手动触发一轮过期扫描（幂等；不冻结归还） |
| `POST /api/ins-valuation-reminders/{id}/ack` | 人工确认提醒（只关提醒，不改估值状态） |
| `GET/PUT /api/policy/valuation-change` | 查看/收紧/放开估值变更留痕入口（台账留痕） |

### 测试

`InsuranceValuationTest`（独立内存库）覆盖：来源/机构/币种/有效期与金额正数校验；
金额修订另出 V2、两签齐备才生效、旧版 SUPERSEDED 金额不改写、出具 INITIAL/ADJUST
批单并回填；提交人不能自审、初审不可重复、未初审不可复核、同人复核拒绝；驳回版本留痕
且只能重开新版本；借展签约自动快照、借展期间估值修订到 80 万 USD 后借展视图仍是
50 万 CNY 签约口径；当前估值过期后扫描自动提醒且幂等，押运四节点+归还验收照常完成、
确认提醒不改估值状态；财务只见金额与凭证（无来源/机构/有效期/批单字段），
公众与普通馆员当前视图、版本履历、借展视图、快照列表全部拒绝且写 `VALUATION_ACCESS_DENY`；
收紧后登记/提交/审批 HTTP 入口与绕过 URL 的服务层直调全部 `PermissionClosedException`
拒绝且无写入、只读不受影响、两开关相互独立，放开后审批链继续生效出批单。

## 文物修复年度报表（museum-16）

修复工作年度归档报表：统计**立项数、完成数、材料消耗、借展次数、异常事件**五项指标，
口径按版本保存，归档即不可变快照；支持撤回重提、缺验收资料标待补、Quartz 失败告警留日志、
按馆区与保密级别裁剪导出。

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_annual_caliber_version` | 指标口径版本（全库 `version_no` 递增）：五项指标的归属时间字段、计入状态、是否扣退料、异常类型等固化为 `caliber_json`；`DRAFT / ACTIVE / SUPERSEDED`，发布新版本旧版本置 SUPERSEDED 只追加 |
| `t_annual_report` | 年度报表归档快照头：`report_no`（`YR-<年度>-<全局6位序号>`）、`report_year/batch_no`（同年度同口径版本内批次递增）、五项指标合计、待补数、口径版本绑定、`GENERATED / REVOKED`、撤回链 `revoked_of_id`，生成后不可变 |
| `t_annual_report_project_line` | 项目明细快照行：固化编号/名称/馆区/保密级/状态/委托人及 `counted_project/counted_completed/accepted/has_accept_docs/pending_supplement` 标志，逐行标明是否计入立项/完成及待补 |
| `t_annual_report_loan_line` | 借展明细快照行：一次借展协议版本签署一行（协议变更重签各计一次），按签署时间归属年度 |
| `t_annual_report_material_line` | 材料消耗快照行：一领用单一行，`issued/returned/net`（净消耗=实际领用−累计退料） |
| `t_annual_report_anomaly` | 异常凭证（**只追加**）：环境异常（经展厅在展文物归属馆区/密级）+ 修复设备故障（经工时→项目→文物反查馆区/密级）；同一异常多次追加 `voucher_seq` 递增，永不覆盖 |
| `t_annual_report_job_log` | Quartz/手动生成任务日志：每次触发一条 `RUNNING → SUCCESS/FAILED`，保留 `fire_key/error_message/起止时间`，失败不删不改 |
| `t_annual_report_alert` | 生成失败告警：失败时追加 `OPEN`，人工确认置 `ACKNOWLEDGED`（只追加，不改写失败日志） |
| `t_annual_report_ledger` | 年度报表台账（只追加）：口径发布/生成/撤回/重提/异常凭证追加/任务失败/告警确认全部留痕 |

### 口径与统计规则

- **五项指标按口径版本统计**（默认口径 V1）：
  - 立项数：项目创建时间落在年度内且状态命中口径（默认 PLANNED/REPAIRING/REPAIRED/ACCEPTED）；
  - 完成数：`finish_date` 落在年度内且状态命中（默认 REPAIRED/ACCEPTED，允许与立项跨年分别计入）；
  - 材料消耗：按领用时间归属，明细行实际用量求和后扣减累计退料；
  - 借展次数：借展协议版本 `SIGNED` 次数（按 `signed_at`，变更重签各算一次）；
  - 异常事件：环境异常（`first_at`/`last_at` 可选）+ 修复设备故障（`fault_at`），类型可在口径中配置。
- **口径白名单**：归属时间字段只能取声明值（CREATE_TIME/FINISH_DATE、USAGE_AT、SIGNED_AT、
  FIRST_AT/LAST_AT、FAULT_AT），非法字段直接拒绝，杜绝列名注入。
- **缺验收资料标待补**：已完成（命中完成口径）但无 museum-14 `PASSED` 成果报告的项目，
  明细行 `pending_supplement=1` 并计入头表 `pending_supplement_count`；不影响其他指标。
- **口径变更不改旧报表**：报表一旦生成即固化绑定口径版本 id；之后发布新口径，已归档报表的
  再核对仍按原版本（快照数字不变），新口径另出新批次。
- **撤回再重提**：撤回只置 `REVOKED`（快照/明细/凭证全保留、可查）；重提另出新批次、
  新报表编号、新生成时间，默认沿用原口径版本（可指定新版本），`revoked_of_id` 串联撤回链——
  编号、统计口径版本、时间三者都可辨。
- **异常只追加凭证**：仅允许给本报表快照已覆盖的异常来源追加凭证（范围外拒绝），序号递增，
  已撤回报表不能再追加；永不更新/覆盖/删除已有凭证。
- **Quartz 失败告警与任务日志**：每次生成（定时或手动）先在独立事务落 RUNNING 日志；
  成功置 SUCCESS，失败在独立事务置 FAILED 并追加 OPEN 告警与台账（不随业务事务回滚、不删日志），
  异常继续抛出。Quartz Job `@DisallowConcurrentExecution` + 服务 `ReentrantLock` 双保险串行。
- **导出裁剪**：CSV 必须声明 `viewerCampus`——先按馆区整行剔除（非本馆区不出库），再按
  `role` 裁剪字段：`REPORT_ADMIN` 见全部密级；`REPORT_READER`（默认）对保密一级（ONE）文物的
  编号/名称/委托人/借展方/异常凭证内容打码，统计事实（计入标志、数量、净消耗）保留。

### 年度报表接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/annual-calibers` | 发布口径新版本（旧版本置 SUPERSEDED；spec 为空用默认口径，字段走白名单校验） |
| `GET /api/annual-calibers[`/active`]` | 口径版本履历 / 当前生效版本 |
| `POST /api/annual-reports/generate` | 手动生成归档快照（同样落任务日志，失败出 FAILED 日志+OPEN 告警） |
| `POST /api/annual-reports/scheduled-fire?year=&campusScope=&generatedBy=` | 模拟 Quartz 定时触发（triggeredBy=SCHEDULED，测试/运维用） |
| `GET /api/annual-reports[?year=]` | 报表列表（含已撤回批次） |
| `GET /api/annual-reports/{id}` | 报表详情：头 + 口径版本/规格 + 项目/借展/材料明细 + 异常凭证 + 待补项目 |
| `POST /api/annual-reports/{id}/revoke` | 撤回归档报表（快照保留，只置 REVOKED） |
| `POST /api/annual-reports/{id}/resubmit` | 撤回后重提：新批次/新编号/新时间，撤回链可辨 |
| `POST /api/annual-reports/{id}/anomalies` | 追加异常凭证（只追加，不覆盖已有凭证） |
| `GET /api/annual-reports/{id}/export?viewerCampus=&role=` | CSV 导出：按馆区整行裁剪 + 按保密级别打码 |
| `GET /api/annual-report-alerts[?status=]` / `POST /api/annual-report-alerts/{id}/ack` | 失败告警列表 / 确认告警 |
| `GET /api/annual-report-job-logs[?year=]` | 生成任务日志（RUNNING/SUCCESS/FAILED 全保留） |

配置：`museum.annual-report.cron`（默认空=不注册 Quartz 触发器，仍可手动生成；
如每年 1 月 5 日 02:00 生成上一年度报表填 `0 0 2 5 1 ?`）。

### 测试

`AnnualReportTest`（独立内存库）覆盖：五项指标按 V1 口径统计、材料净消耗扣退料、
缺 PASSED 成果报告项目标待补、年度边界（上年最后一秒/下年零点）归属；先生成报表后发布
V2 收紧口径（完成数仅 ACCEPTED），旧报表仍按 V1 核对且数字不变、新口径另出新批次；
Quartz 模拟触发 2024 报 SCHEDULED 日志；错误口径版本生成失败保留 FAILED 日志并出 OPEN 告警、
确认后日志不改写；撤回后重提编号（YR 序号递增）、批次、口径版本、生成时间与撤回链全部可辨、
旧批 REVOKED 保留；异常凭证两次追加序号 1/2 首份不被覆盖、范围外异常与已撤回报表拒绝追加；
CSV 对 MAIN/EAST 馆区整行裁剪、REPORT_READER 对一级文物打码而 REPORT_ADMIN 见明文、
未声明馆区拒绝导出。
