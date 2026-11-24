#!/usr/bin/env bash
# 省食品安全平台报文交换服务 —— 样例场景端到端验证脚本
# 前置：服务已启动且数据库为空（全新部署）。用法: ./verify.sh [BASE_URL]
set -u

BASE="${1:-${BASE_URL:-http://localhost:8080}}"
AUTH="${API_AUTH:-admin:admin123}"
PY="${PYTHON:-python3}"
WORK="$(mktemp -d)"
PASS=0
FAIL=0

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); say "  PASS  $1"; }
bad()  { FAIL=$((FAIL+1)); say "  FAIL  $1"; }
check(){ [ "$2" = "$3" ] && ok "$1" || { bad "$1 （期望=$3 实际=$2）"; }; }

# 提取 JSON 字段：jval '<python 表达式，变量 d 为解析后的 JSON>'
jval() { "$PY" -c "import json,sys;d=json.load(sys.stdin);print($1)"; }

sign() { "$PY" -c "import hmac,hashlib,sys;print(hmac.new(sys.argv[2].encode(),open(sys.argv[1],'rb').read(),hashlib.sha256).hexdigest())" "$1" "$2"; }

ingest() { # containerId batchId sender secret file [attachments]
  local sig; sig=$(sign "$5" "$4")
  if [ $# -ge 6 ]; then
    curl -s -u "$AUTH" -F "containerId=$1" -F "batchId=$2" -F "sender=$3" -F "signature=$sig" -F "attachments=$6" -F "file=@$5" "$BASE/api/inbound/containers"
  else
    curl -s -u "$AUTH" -F "containerId=$1" -F "batchId=$2" -F "sender=$3" -F "signature=$sig" -F "file=@$5" "$BASE/api/inbound/containers"
  fi
}

deliver() { # operationNo reportNo revisionNo deliveryType payloadJson
  curl -s -u "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"operationNo\":\"$1\",\"reportNo\":\"$2\",\"revisionNo\":$3,\"deliveryType\":\"$4\",\"payload\":$5}" \
    "$BASE/api/deliveries"
}

say "== 0. 前置检查 =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" "$BASE/api/meta/spec-versions")
check "服务可达且需认证" "$CODE" "200"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/meta/spec-versions")
check "未认证访问被拒绝" "$CODE" "401"

say "== 1. 容器 C1：样品 S17 / 报告 R7 / 修订1（CSV1 规范，批次 B2026-01）=="
cat > "$WORK/c1.csv" <<'CSV'
sample_key,report_no,revision_no,supersedes,analyte,result_value,result_unit,detection_limit,detection_limit_unit,conclusion,attachment_refs
S17,R7,1,,铅,0.02,mg/kg,0.005,mg/kg,PASS,
CSV
R=$(ingest C1 B2026-01 lab-alpha lab-alpha-secret "$WORK/c1.csv")
check "C1 修订1 已受理" "$(echo "$R" | jval "d['entries'][0]['status']")" "ACCEPTED"
check "C1 按 CSV1 解析" "$(echo "$R" | jval "d['specVersion']")" "CSV1"

say "== 2. 操作 O1 投递修订1 -> 已受理 =="
R=$(deliver O1 R7 1 REPORT '{"reportNo":"R7","revisionNo":1,"conclusion":"PASS"}')
check "O1 已受理" "$(echo "$R" | jval "d['status']")" "ACCEPTED"
check "O1 回执类型为 RECEIVED（仅收到）" "$(echo "$R" | jval "d['receiptType']")" "RECEIVED"

say "== 3. 复检修订2 显式取代修订1（容器 C2）=="
cat > "$WORK/c2.csv" <<'CSV'
sample_key,report_no,revision_no,supersedes,analyte,result_value,result_unit,detection_limit,detection_limit_unit,conclusion,attachment_refs
S17,R7,2,1,铅,0.019,mg/kg,0.005,mg/kg,PASS,
CSV
R=$(ingest C2 B2026-01 lab-alpha lab-alpha-secret "$WORK/c2.csv")
check "C2 修订2 已受理" "$(echo "$R" | jval "d['entries'][0]['status']")" "ACCEPTED"
R=$(curl -s -u "$AUTH" "$BASE/api/projections/reports/R7")
check "链头为修订2" "$(echo "$R" | jval "d['head']")" "2"
check "修订1 已被取代" "$(echo "$R" | jval "[r['status'] for r in d['revisions'] if r['revisionNo']==1][0]")" "SUPERSEDED"

say "== 4. O2 投递修订2，目标超时 -> 未知 =="
curl -s -X POST -H 'Content-Type: application/json' -d '{"timeoutNext":true}' "$BASE/target/faults" > /dev/null
R=$(deliver O2 R7 2 REPORT '{"reportNo":"R7","revisionNo":2,"conclusion":"PASS"}')
check "O2 状态为超时未知" "$(echo "$R" | jval "d['status']")" "UNKNOWN"
UNKNOWN_SEQ=$(echo "$R" | jval "d['eventSeq']")

say "== 5. 重发 O2：先按原操作号查询，目标已受理 -> 本地收敛，不新建操作 =="
R=$(curl -s -u "$AUTH" -X POST "$BASE/api/deliveries/O2/retry")
check "O2 收敛为已受理" "$(echo "$R" | jval "d['status']")" "ACCEPTED"
N=$(curl -s "$BASE/target/inbox" | jval "len([r for r in d if r['operationNo']=='O2'])")
check "目标端只有一次 O2 写入（未新建操作）" "$N" "1"
R=$(curl -s -u "$AUTH" "$BASE/api/replay/operations/O2?asOfSeq=$UNKNOWN_SEQ")
check "重放至超时时刻 O2 为未知" "$(echo "$R" | jval "d['status']")" "UNKNOWN"
R=$(curl -s -u "$AUTH" "$BASE/api/replay/operations/O2")
check "重放至当前 O2 为已受理" "$(echo "$R" | jval "d['status']")" "ACCEPTED"

say "== 6. 旧容器 C1 重传：记录重复到达，不回滚至修订1 =="
R=$(ingest C1 B2026-01 lab-alpha lab-alpha-secret "$WORK/c1.csv")
check "C1 识别为重传" "$(echo "$R" | jval "d['retransmitted']")" "True"
check "重传条目为重复到达" "$(echo "$R" | jval "d['entries'][0]['status']")" "DUPLICATE_ARRIVAL"
R=$(curl -s -u "$AUTH" "$BASE/api/projections/reports/R7")
check "链头仍为修订2" "$(echo "$R" | jval "d['head']")" "2"

say "== 7. 操作号幂等：同号同载荷返回原结果；同号不同载荷冲突；异号同修订同摘要记重复 =="
R=$(deliver O2 R7 2 REPORT '{"reportNo":"R7","revisionNo":2,"conclusion":"PASS"}')
check "O2 同载荷重发返回原结果" "$(echo "$R" | jval "(d['status'],d['replayed'])")" "('ACCEPTED', True)"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" -H 'Content-Type: application/json' \
  -d '{"operationNo":"O2","reportNo":"R7","revisionNo":2,"deliveryType":"REPORT","payload":{"reportNo":"R7","revisionNo":2,"conclusion":"FAIL"}}' \
  "$BASE/api/deliveries")
check "O2 不同载荷返回 409 冲突" "$CODE" "409"
R=$(deliver O9 R7 2 REPORT '{"reportNo":"R7","revisionNo":2,"conclusion":"PASS"}')
check "O9 同修订同摘要记重复到达" "$(echo "$R" | jval "d['status']")" "DUPLICATE"
check "O9 指向原操作 O2" "$(echo "$R" | jval "d['duplicateOf']")" "O2"

say "== 8. 修订3：新批次 B2026-11 生效的 XML2 规范（容器 C3）=="
cat > "$WORK/c3.xml" <<'XML'
<reportBatch>
  <report>
    <sampleKey>S17</sampleKey>
    <reportNo>R7</reportNo>
    <revision>3</revision>
    <supersedes><rev>2</rev></supersedes>
    <analyte>铅</analyte>
    <result><value>0.018</value><unit>mg/kg</unit></result>
    <detectionLimit><value>0.004</value><unit>mg/kg</unit></detectionLimit>
    <conclusion>PASS</conclusion>
    <attachments><ref>att-9</ref></attachments>
  </report>
</reportBatch>
XML
R=$(ingest C3 B2026-11 lab-alpha lab-alpha-secret "$WORK/c3.xml" '[{"id":"att-9","sha256":"deadbeef"}]')
check "C3 修订3 已受理" "$(echo "$R" | jval "d['entries'][0]['status']")" "ACCEPTED"
check "C3 按 XML2 解析" "$(echo "$R" | jval "d['specVersion']")" "XML2"
R=$(curl -s -u "$AUTH" "$BASE/api/projections/reports/R7")
check "链头为修订3" "$(echo "$R" | jval "d['head']")" "3"
check "修订2 已被取代" "$(echo "$R" | jval "[r['status'] for r in d['revisions'] if r['revisionNo']==2][0]")" "SUPERSEDED"
check "修订3 投影固定为 XML2 规范" "$(echo "$R" | jval "[r['specVersion'] for r in d['revisions'] if r['revisionNo']==3][0]")" "XML2"
check "修订1 投影保留 CSV1 规范（换版不回溯）" "$(echo "$R" | jval "[r['specVersion'] for r in d['revisions'] if r['revisionNo']==1][0]")" "CSV1"

say "== 9. 签名有效但检出限单位缺失 -> 业务拒绝而非解析成功（容器 C4）=="
cat > "$WORK/c4.csv" <<'CSV'
sample_key,report_no,revision_no,supersedes,analyte,result_value,result_unit,detection_limit,detection_limit_unit,conclusion,attachment_refs
S18,R9,1,,镉,0.05,mg/kg,0.01,,PASS,
CSV
R=$(ingest C4 B2026-01 lab-alpha lab-alpha-secret "$WORK/c4.csv")
check "C4 签名有效" "$(echo "$R" | jval "d['signatureValid']")" "True"
check "C4 业务拒绝" "$(echo "$R" | jval "d['entries'][0]['status']")" "BUSINESS_REJECTED"
check "C4 拒绝原因为缺检出限单位" "$(echo "$R" | jval "d['entries'][0]['reason']")" "MISSING_DETECTION_LIMIT_UNIT"

say "== 10. 缺少 supersedes 的并行新结论 -> 隔离（容器 C5）=="
cat > "$WORK/c5.csv" <<'CSV'
sample_key,report_no,revision_no,supersedes,analyte,result_value,result_unit,detection_limit,detection_limit_unit,conclusion,attachment_refs
S17,R7,4,,铅,0.017,mg/kg,0.004,mg/kg,PASS,
CSV
R=$(ingest C5 B2026-01 lab-alpha lab-alpha-secret "$WORK/c5.csv")
check "C5 修订4 被隔离" "$(echo "$R" | jval "d['entries'][0]['status']")" "QUARANTINED"
R=$(curl -s -u "$AUTH" "$BASE/api/projections/reports/R7")
check "链头仍为修订3" "$(echo "$R" | jval "d['head']")" "3"

say "== 11. 较旧修订晚到不覆盖新修订（报告 R8：先收修订2 再收修订1）=="
cat > "$WORK/c6.csv" <<'CSV'
sample_key,report_no,revision_no,supersedes,analyte,result_value,result_unit,detection_limit,detection_limit_unit,conclusion,attachment_refs
S20,R8,2,1,汞,0.01,mg/kg,0.002,mg/kg,FAIL,
CSV
R=$(ingest C6 B2026-01 lab-beta lab-beta-secret "$WORK/c6.csv")
check "R8 修订2 先达并受理" "$(echo "$R" | jval "d['entries'][0]['status']")" "ACCEPTED"
cat > "$WORK/c7.csv" <<'CSV'
sample_key,report_no,revision_no,supersedes,analyte,result_value,result_unit,detection_limit,detection_limit_unit,conclusion,attachment_refs
S20,R8,1,,汞,0.011,mg/kg,0.002,mg/kg,FAIL,
CSV
R=$(ingest C7 B2026-01 lab-beta lab-beta-secret "$WORK/c7.csv")
check "R8 修订1 晚到被忽略" "$(echo "$R" | jval "d['entries'][0]['status']")" "LATE_REVISION_IGNORED"
R=$(curl -s -u "$AUTH" "$BASE/api/projections/reports/R8")
check "R8 链头仍为修订2" "$(echo "$R" | jval "d['head']")" "2"

say "== 12. 同修订号不同摘要 -> 修订冲突（容器 C8）=="
cat > "$WORK/c8.csv" <<'CSV'
sample_key,report_no,revision_no,supersedes,analyte,result_value,result_unit,detection_limit,detection_limit_unit,conclusion,attachment_refs
S17,R7,3,2,铅,0.021,mg/kg,0.004,mg/kg,PASS,
CSV
R=$(ingest C8 B2026-01 lab-alpha lab-alpha-secret "$WORK/c8.csv")
check "C8 修订3 内容冲突" "$(echo "$R" | jval "d['entries'][0]['status']")" "REVISION_CONFLICT"

say "== 13. 签名无效 -> 解析失败（容器 C9）=="
R=$(curl -s -u "$AUTH" -F containerId=C9 -F batchId=B2026-01 -F sender=lab-alpha -F signature=0000 -F "file=@$WORK/c1.csv" "$BASE/api/inbound/containers")
check "C9 签名无效" "$(echo "$R" | jval "d['signatureValid']")" "False"
check "C9 容器状态为解析失败" "$(echo "$R" | jval "d['containerStatus']")" "PARSE_FAILED"

say "== 14. 未知附件引用 -> 解析失败（容器 C10）=="
cat > "$WORK/c10.xml" <<'XML'
<reportBatch>
  <report>
    <sampleKey>S21</sampleKey>
    <reportNo>R10</reportNo>
    <revision>1</revision>
    <analyte>砷</analyte>
    <result><value>0.03</value><unit>mg/kg</unit></result>
    <conclusion>PASS</conclusion>
    <attachments><ref>att-x</ref></attachments>
  </report>
</reportBatch>
XML
R=$(ingest C10 B2026-11 lab-alpha lab-alpha-secret "$WORK/c10.xml" '[{"id":"att-9","sha256":"deadbeef"}]')
check "C10 容器状态为解析失败" "$(echo "$R" | jval "d['containerStatus']")" "PARSE_FAILED"

say "== 15. 处置类投递回执类型区分（O3 -> DISPOSAL_CONFIRMED）=="
R=$(deliver O3 R7 3 DISPOSAL '{"reportNo":"R7","revisionNo":3,"action":"DISPOSE"}')
check "O3 已受理" "$(echo "$R" | jval "d['status']")" "ACCEPTED"
check "O3 回执类型为 DISPOSAL_CONFIRMED（确认处置）" "$(echo "$R" | jval "d['receiptType']")" "DISPOSAL_CONFIRMED"

say "== 16. 事件重放 vs 版本投影：当前状态、修订历史、时点结果一致 =="
MAX_SEQ=$(curl -s -u "$AUTH" "$BASE/api/replay/reports/R7" | jval "d['asOfSeq']")
R=$(curl -s -u "$AUTH" "$BASE/api/replay/reports/R7/verify")
check "R7 重放与投影一致" "$(echo "$R" | jval "d['consistent']")" "True"
R=$(curl -s -u "$AUTH" "$BASE/api/replay/reports/R8/verify")
check "R8 重放与投影一致" "$(echo "$R" | jval "d['consistent']")" "True"
R=$(curl -s -u "$AUTH" "$BASE/api/replay/reports/R7")
check "重放当前链头为修订3" "$(echo "$R" | jval "d['head']")" "3"
check "重放可见全部修订历史" "$(echo "$R" | jval "sorted(r['revisionNo'] for r in d['revisions'])")" "[1, 2, 3, 4]"

say "== 17. 投影重建与事件账一致（含重建差异报告）=="
R=$(curl -s -u "$AUTH" -X POST "$BASE/api/projections/rebuild")
check "重建后投影无差异" "$(echo "$R" | jval "d['projectionDifferences']")" "[]"
check "重建后操作账一致" "$(echo "$R" | jval "d['operationMismatches']")" "[]"
check "规范换版边界无违例" "$(echo "$R" | jval "d['specBoundaryViolations']")" "[]"
check "重建整体一致" "$(echo "$R" | jval "d['consistent']")" "True"

say "== 18. 对账证据链 =="
R=$(curl -s -u "$AUTH" "$BASE/api/reconciliation/containers/C1")
check "C1 证据链含信封" "$(echo "$R" | jval "d['container']['containerId']")" "C1"
check "C1 证据链含样品 S17/报告 R7" "$(echo "$R" | jval "(d['entries'][0]['sampleKey'],d['entries'][0]['reportNo'])")" "('S17', 'R7')"
R=$(curl -s -u "$AUTH" "$BASE/api/reconciliation/operations/O2")
check "O2 证据链含回执" "$(echo "$R" | jval "len(d['operation']['receipts'])>0")" "True"
check "O2 证据链关联报告修订" "$(echo "$R" | jval "(d['reportRevision']['reportNo'],d['reportRevision']['revisionNo'])")" "('R7', 2)"
R=$(curl -s -u "$AUTH" "$BASE/api/reconciliation/reports/R7")
check "R7 证据链含操作 O1/O2/O3" "$(echo "$R" | jval "sorted(o['operationNo'] for o in d['operations'])")" "['O1', 'O2', 'O3', 'O9']"
R=$(curl -s -u "$AUTH" "$BASE/api/reconciliation/samples/S17")
check "S17 证据链含报告 R7" "$(echo "$R" | jval "[h['reportNo'] for h in d['heads']]")" "['R7']"

say ""
say "============== 污染源头候选推导（三工厂共用供应商样例） =============="
R=$(curl -s -u "$AUTH" -X POST "$BASE/api/trace/demo/seed")
check "样例导入：3 家工厂" "$(echo "$R" | jval "d['graph']['factories']")" "3"

R=$(curl -s -u "$AUTH" -X POST -H 'Content-Type: application/json' -d '{"analyte":"LEAD"}' "$BASE/api/trace/evaluate")
check "候选推导恒为待调查候选（非法责任认定）" "$(echo "$R" | jval "d['legalLiability']")" "False"
check "阳性样品 3 个" "$(echo "$R" | jval "d['positiveSamples']")" "3"
check "时间剪枝：晚到批次不可达" "$(echo "$R" | jval "sorted(p['rawBatchId'] for p in d['pruned'] if p['reason']=='TIME_UNREACHABLE')")" "['RB-STARCH-LATE']"
check "指纹剪枝：第三家同辅料批次不符" "$(echo "$R" | jval "sorted(p['rawBatchId'] for p in d['pruned'] if p['reason']=='FINGERPRINT_INCOMPATIBLE')")" "['RB-STARCH-OTHER']"
check "第三家样品因全路径被剪枝而无法解释" "$(echo "$R" | jval "d['unresolved'][0]['reason']")" "ALL_PATHS_PRUNED"
check "两厂共用批次位列候选首位" "$(echo "$R" | jval "d['candidates'][0]['rawBatchId']")" "RB-STARCH-OK"
check "首位候选由两个样品支持" "$(echo "$R" | jval "len(d['candidates'][0]['supportedBy'])")" "2"
check "首位候选非单次阳性" "$(echo "$R" | jval "d['candidates'][0]['singleObservation']")" "False"
check "缺失批次记录导致范围扩张" "$(echo "$R" | jval "d['status']")" "SCOPE_EXPANDED"
check "展开候选标记缺失批次记录" "$(echo "$R" | jval "any(c['viaMissingRecord'] for c in d['candidates'])")" "True"
check "共用供应商分组含三家工厂" "$(echo "$R" | jval "[len(g['factories']) for g in d['sharedSupplierGroups'] if g['supplierId']=='SUP-SHARED'][0]")" "3"
check "共用供应商说明不构成同源" "$(echo "$R" | jval "'不构成同源' in [g['note'] for g in d['sharedSupplierGroups'] if g['supplierId']=='SUP-SHARED'][0]")" "True"
CONSTRAINT_N=$(echo "$R" | jval "d['candidateCount']")

R=$(curl -s -u "$AUTH" -X POST -H 'Content-Type: application/json' -d '{"analyte":"LEAD","useFingerprint":false}' "$BASE/api/trace/evaluate")
NOFP_N=$(echo "$R" | jval "d['candidateCount']")
check "关闭指纹证据后候选范围扩张" "$([ "$NOFP_N" -gt "$CONSTRAINT_N" ] && echo yes || echo no)" "yes"
check "关闭指纹证据后指纹不符批次进入候选" "$(echo "$R" | jval "'RB-STARCH-OTHER' in [c['rawBatchId'] for c in d['candidates']]")" "True"

R=$(curl -s -u "$AUTH" -X POST -H 'Content-Type: application/json' -d '{"analyte":"LEAD","mode":"PROBABILISTIC","backgroundRate":0.05}' "$BASE/api/trace/evaluate")
check "概率模式首位同为两厂共用批次" "$(echo "$R" | jval "d['candidates'][0]['rawBatchId']")" "RB-STARCH-OK"
check "概率模式给出后验权重" "$(echo "$R" | jval "d['candidates'][0]['posterior'] > 0")" "True"
check "概率模式标明背景污染率非默认" "$(echo "$R" | jval "d['assumptions']['backgroundRateDefaulted']")" "False"

R=$(curl -s -u "$AUTH" -X POST -H 'Content-Type: application/json' -d '{"analyte":"LEAD","asOf":"2026-03-20T10:06:00Z"}' "$BASE/api/trace/evaluate")
check "结果时点前置：仅 1 个阳性" "$(echo "$R" | jval "d['positiveSamples']")" "1"
check "结果时点前置：首位候选为单次阳性" "$(echo "$R" | jval "d['candidates'][0]['singleObservation']")" "True"

R=$(curl -s -u "$AUTH" -X POST -H 'Content-Type: application/json' -d '{"analyte":"LEAD","purpose":"RISK_COMMUNICATION"}' "$BASE/api/trace/cases/CASE-LEAD-0326/versions")
VID=$(echo "$R" | jval "d['versionId']")
check "追溯版本冻结生产图摘要" "$(echo "$R" | jval "len(d['graphSha256'])")" "64"
check "追溯版本冻结实验结果摘要" "$(echo "$R" | jval "len(d['spectraSha256'])")" "64"
check "追溯版本非法律定责" "$(echo "$R" | jval "d['legalLiability']")" "False"
R=$(curl -s -u "$AUTH" "$BASE/api/trace/cases/CASE-LEAD-0326/versions/$VID/verify")
check "追溯版本可仅凭冻结输入复现" "$(echo "$R" | jval "d['reproducible']")" "True"

R=$(curl -s -u "$AUTH" -X POST "$BASE/api/trace/demo/backfill-usage?rawBatchId=RB-EM-01")
check "补登批次记录影响 1 条投料" "$(echo "$R" | jval "d['updatedUsages']")" "1"
R=$(curl -s -u "$AUTH" -X POST -H 'Content-Type: application/json' -d '{"analyte":"LEAD"}' "$BASE/api/trace/evaluate")
check "补登后展开候选收敛为直接路径" "$(echo "$R" | jval "[c['viaMissingRecord'] for c in d['candidates'] if c['rawBatchId']=='RB-EM-01'][0]")" "False"
check "补登后另一同辅料批次退出候选" "$(echo "$R" | jval "'RB-EM-02' not in [c['rawBatchId'] for c in d['candidates']]")" "True"

say ""
say "==============================================="
say "结果: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && say "ALL CHECKS PASSED" || say "SOME CHECKS FAILED"
rm -rf "$WORK"
exit "$FAIL"
