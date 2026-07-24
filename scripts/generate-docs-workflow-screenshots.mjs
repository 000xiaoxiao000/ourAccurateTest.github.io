import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const rootDir = resolve(__dirname, '..')
const frontendDir = resolve(rootDir, 'oAT-web-frontend')
const outputDir = resolve(rootDir, 'docs/assets')
const chromePath = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

const now = '2026-07-25T09:30:00'
const projectId = 'demo'
const baselineId = 'base-release'

const user = { id: 'u1', name: 'qa.lead', nickname: '测试负责人', email: 'qa@example.com' }
const project = {
  id: projectId,
  name: '支付中心精准测试',
  describe: '登录、支付、退款链路发布前验证',
  memberCount: 8,
  createTime: now,
  updateTime: now,
}
const apps = [
  {
    id: 'app-pay',
    name: 'payment-service',
    srcName: 'payment-service',
    language: 'Java',
    describe: '支付核心服务',
    range: 'src/main/java',
    onlineCount: 3,
    currentVersion: '2.8.0',
    currentBranch: 'release/2.8',
    currentCommitId: '9f42a7c8d1e6',
    repoConfigured: true,
    sourceType: 'GIT',
  },
]

const assets = {
  requirement: asset('req-1', 'REQUIREMENT', '支付需求-v2.8.md', 'REQ-PAY-001 支付成功后应生成交易流水，并在 3 秒内返回支付结果。'),
  testcase: asset('tc-1', 'TESTCASE', '支付回归用例.xlsx', 'TC-PAY-001 正常支付；TC-PAY-014 重复支付拦截；TC-PAY-021 支付超时补偿。'),
  source: asset('src-1', 'SOURCE', 'payment-service@9f42a7c', 'PaymentController.java, PaymentService.java, LedgerRepository.java'),
  execution: asset('exec-1', 'EXECUTION', '接口自动化执行依据.json', 'Postman/Newman 执行 42 条，失败 1 条，失败项为支付超时补偿。'),
  coverage: asset('cov-1', 'COVERAGE', 'jacoco-runtime.xml', '运行时覆盖率：行覆盖 84.2%，分支覆盖 71.5%。'),
}

const baseline = {
  id: baselineId,
  projectId,
  name: '支付中心 v2.8 发布前验证',
  requirementAssetId: assets.requirement.id,
  testcaseAssetId: assets.testcase.id,
  sourceAssetId: assets.source.id,
  executionAssetId: assets.execution.id,
  coverageAssetId: assets.coverage.id,
  sourceAppId: apps[0].id,
  repositoryUrl: 'https://git.example.com/payment/payment-service.git',
  sourceBranch: 'release/2.8',
  sourceCommit: '9f42a7c8d1e6',
  analyzerVersion: 'oAT-analyzer-1.0',
  status: 'WAITING_REVIEW',
  freshness: 'LIVE',
  createTime: now,
  updateTime: now,
}

const criteria = [
  { id: 'ac-1', requirementKey: 'REQ-PAY-001', acKey: 'AC-01', title: '支付成功返回', content: '支付成功后 3 秒内返回 SUCCESS，并生成唯一交易流水。', priority: 'P0', testable: true, ambiguity: false, confidence: 0.94 },
  { id: 'ac-2', requirementKey: 'REQ-PAY-001', acKey: 'AC-02', title: '重复支付拦截', content: '同一订单重复提交时返回幂等结果，不重复扣款。', priority: 'P0', testable: true, ambiguity: false, confidence: 0.91 },
  { id: 'ac-3', requirementKey: 'REQ-PAY-002', acKey: 'AC-03', title: '超时补偿', content: '渠道超时后进入补偿任务，并记录可追踪状态。', priority: 'P1', testable: true, ambiguity: false, confidence: 0.86 },
]
const testcases = [
  { id: 'tc-1', externalKey: 'TC-PAY-001', title: '正常支付成功', steps: '创建订单 -> 发起支付 -> 查询流水', expected: '返回 SUCCESS 且流水状态为 PAID', requirementRefs: 'REQ-PAY-001' },
  { id: 'tc-2', externalKey: 'TC-PAY-014', title: '重复支付幂等校验', steps: '同一订单连续支付两次', expected: '第二次返回已有支付结果', requirementRefs: 'REQ-PAY-001' },
  { id: 'tc-3', externalKey: 'TC-PAY-021', title: '支付超时补偿', steps: '模拟渠道超时 -> 等待补偿任务', expected: '补偿任务落库并可查询', requirementRefs: 'REQ-PAY-002' },
]
const traceLinks = [
  link('l1', 'ACCEPTANCE_CRITERION', 'ac-1', 'TESTCASE', 'tc-1', 'VERIFIED_BY', 0.94),
  link('l2', 'ACCEPTANCE_CRITERION', 'ac-2', 'TESTCASE', 'tc-2', 'VERIFIED_BY', 0.9),
  link('l3', 'ACCEPTANCE_CRITERION', 'ac-3', 'TESTCASE', 'tc-3', 'VERIFIED_BY', 0.72),
]
const findings = [
  {
    id: 'f-1',
    acId: 'ac-3',
    findingType: 'IMPLEMENTATION_GAP',
    perspective: 'CROSS',
    severity: 'HIGH',
    title: '超时补偿链路缺少失败重试断言',
    description: '执行依据显示补偿任务被触发，但用例只校验任务创建，没有校验重试完成后的最终状态。',
    suggestion: '补充重试成功与重试失败两个断言，并关联补偿任务状态表。',
    confidence: 0.87,
    evidenceLevel: 'E3',
    verdict: 'PARTIAL',
    reviewStatus: 'PENDING',
  },
  {
    id: 'f-2',
    acId: 'ac-2',
    findingType: 'WEAK_ASSERTION',
    perspective: 'TEST',
    severity: 'MEDIUM',
    title: '重复支付用例未覆盖并发提交',
    description: '当前执行依据覆盖连续请求，但没有覆盖两个请求同时进入支付服务的场景。',
    suggestion: '增加并发支付接口自动化场景，校验唯一流水和幂等锁。',
    confidence: 0.79,
    evidenceLevel: 'E2',
    verdict: 'AMBIGUOUS',
    reviewStatus: 'PENDING',
  },
]
const metrics = {
  totalCriteria: 3,
  coveredCriteria: 3,
  implementedCriteria: 2,
  executedCriteria: 3,
  coveredByRuntimeCriteria: 2,
  closedLoopCriteria: 2,
  openFindings: 2,
  testcaseCoverageRate: 1,
  implementationCoverageRate: 0.86,
  executionEvidenceRate: 1,
  runtimeCoverageRate: 0.84,
  closedLoopRate: 0.67,
  staticCodeCount: 28,
  dynamicCodeCount: 19,
  coverageFileCount: 12,
  coveredLines: 1428,
  totalLines: 1695,
  lineCoverageRate: 0.842,
  coveredBranches: 186,
  totalBranches: 260,
  branchCoverageRate: 0.715,
}

const overview = {
  requirements: [assets.requirement],
  testcases: [assets.testcase],
  sources: [assets.source],
  executions: [assets.execution],
  coverages: [assets.coverage],
  defects: [],
  baselines: [baseline],
}

const traceabilityNodes = [
  traceabilityNode('req-pay-001', 'REQUIREMENT', 'REQ-PAY-001 支付成功返回', '需求要求 3 秒内返回 SUCCESS，并落交易流水。', 'requirements', 'REQ-PAY-001', 'BOTH'),
  traceabilityNode('req-pay-002', 'REQUIREMENT', 'REQ-PAY-002 超时补偿', '渠道超时后需要触发补偿任务，并提供可追踪状态。', 'requirements', 'REQ-PAY-002', 'STATIC'),
  traceabilityNode('tc-pay-001', 'TESTCASE', 'TC-PAY-001 正常支付成功', '覆盖支付成功主链路与流水落库。', 'testcases', 'cases.xlsx#12', 'BOTH'),
  traceabilityNode('tc-pay-014', 'TESTCASE', 'TC-PAY-014 重复支付幂等校验', '覆盖重复提交幂等返回。', 'testcases', 'cases.xlsx#27', 'STATIC'),
  traceabilityNode('tc-pay-021', 'TESTCASE', 'TC-PAY-021 支付超时补偿', '覆盖补偿任务创建与状态追踪。', 'testcases', 'cases.xlsx#41', 'DYNAMIC'),
  traceabilityNode('code-file-payment', 'CODE_FILE', 'PaymentService.java', '支付服务核心实现文件。', 'code', 'src/main/java/com/oat/payment/PaymentService.java', 'BOTH', { path: 'src/main/java/com/oat/payment/PaymentService.java' }),
  traceabilityNode('code-class-payment', 'CODE_CLASS', 'PaymentService', '支付服务领域编排类。', 'code', 'com.oat.payment.PaymentService', 'BOTH', { path: 'src/main/java/com/oat/payment/PaymentService.java' }, 'code-file-payment'),
  traceabilityNode('code-method-pay', 'CODE_METHOD', 'pay(orderId, amount)', '执行支付、落库并返回支付结果。', 'code', 'PaymentService.pay', 'BOTH', { path: 'src/main/java/com/oat/payment/PaymentService.java:70', line: 70 }, 'code-class-payment', { coveredLines: 52, totalLines: 59, lineRate: 0.88, coveredBranches: 8, totalBranches: 10, branchRate: 0.8 }),
  traceabilityNode('code-method-idempotent', 'CODE_METHOD', 'ensureIdempotent(orderId)', '检查重复支付并返回幂等结果。', 'code', 'PaymentService.ensureIdempotent', 'STATIC', { path: 'src/main/java/com/oat/payment/PaymentService.java:112', line: 112 }, 'code-class-payment', { coveredLines: 18, totalLines: 24, lineRate: 0.75, coveredBranches: 3, totalBranches: 4, branchRate: 0.75 }),
  traceabilityNode('code-file-timeout', 'CODE_FILE', 'PaymentTimeoutJob.java', '补偿任务执行入口。', 'code', 'src/main/java/com/oat/payment/PaymentTimeoutJob.java', 'DYNAMIC', { path: 'src/main/java/com/oat/payment/PaymentTimeoutJob.java' }),
  traceabilityNode('code-class-timeout', 'CODE_CLASS', 'PaymentTimeoutJob', '超时补偿任务处理类。', 'code', 'com.oat.payment.PaymentTimeoutJob', 'DYNAMIC', { path: 'src/main/java/com/oat/payment/PaymentTimeoutJob.java' }, 'code-file-timeout'),
  traceabilityNode('code-method-retry', 'CODE_METHOD', 'retry(paymentId)', '对超时支付执行补偿与重试。', 'code', 'PaymentTimeoutJob.retry', 'DYNAMIC', { path: 'src/main/java/com/oat/payment/PaymentTimeoutJob.java:32', line: 32 }, 'code-class-timeout', { coveredLines: 26, totalLines: 39, lineRate: 0.67, coveredBranches: 4, totalBranches: 8, branchRate: 0.5 }),
]

const traceabilityEdges = [
  traceabilityEdge('te-1', 'req-pay-001', 'tc-pay-001', 'VERIFIED_BY', 'DOCUMENT', 'BOTH', 0.96),
  traceabilityEdge('te-2', 'req-pay-001', 'tc-pay-014', 'VERIFIED_BY', 'AI', 'STATIC', 0.89),
  traceabilityEdge('te-3', 'req-pay-002', 'tc-pay-021', 'VERIFIED_BY', 'EXECUTION_TRACE', 'DYNAMIC', 0.86),
  traceabilityEdge('te-4', 'tc-pay-001', 'code-method-pay', 'COVERS', 'COVERAGE', 'DYNAMIC_CONFIRMED', 0.93),
  traceabilityEdge('te-5', 'tc-pay-014', 'code-method-idempotent', 'COVERS', 'STATIC_ANALYSIS', 'STATIC_BRIDGED', 0.81),
  traceabilityEdge('te-6', 'tc-pay-021', 'code-method-retry', 'COVERS', 'EXECUTION_TRACE', 'DYNAMIC_CONFIRMED', 0.9),
  traceabilityEdge('te-7', 'req-pay-001', 'code-method-pay', 'IMPLEMENTED_BY', 'STATIC_ANALYSIS', 'BOTH', 0.88),
  traceabilityEdge('te-8', 'req-pay-002', 'code-method-retry', 'IMPLEMENTED_BY', 'STATIC_ANALYSIS', 'STATIC', 0.84),
  traceabilityEdge('te-9', 'code-method-pay', 'code-method-idempotent', 'CALLS', 'DERIVED', 'STATIC_ONLY', 0.82),
]

const traceabilityCodeTree = [
  {
    id: 'dir-src-main-java',
    kind: 'DIRECTORY',
    label: 'src/main/java/com/oat/payment',
    path: 'src/main/java/com/oat/payment',
    evidenceState: 'BOTH',
    children: [
      {
        id: 'code-file-payment',
        kind: 'FILE',
        label: 'PaymentService.java',
        path: 'src/main/java/com/oat/payment/PaymentService.java',
        language: 'Java',
        evidenceState: 'BOTH',
        children: [
          {
            id: 'code-class-payment',
            kind: 'CLASS',
            label: 'PaymentService',
            path: 'com.oat.payment.PaymentService',
            language: 'Java',
            evidenceState: 'BOTH',
            children: [
              { id: 'code-method-pay', kind: 'METHOD', label: 'pay(orderId, amount)', path: 'PaymentService.pay', language: 'Java', evidenceState: 'BOTH', children: [] },
              { id: 'code-method-idempotent', kind: 'METHOD', label: 'ensureIdempotent(orderId)', path: 'PaymentService.ensureIdempotent', language: 'Java', evidenceState: 'STATIC', children: [] },
            ],
          },
        ],
      },
      {
        id: 'code-file-timeout',
        kind: 'FILE',
        label: 'PaymentTimeoutJob.java',
        path: 'src/main/java/com/oat/payment/PaymentTimeoutJob.java',
        language: 'Java',
        evidenceState: 'DYNAMIC',
        children: [
          {
            id: 'code-class-timeout',
            kind: 'CLASS',
            label: 'PaymentTimeoutJob',
            path: 'com.oat.payment.PaymentTimeoutJob',
            language: 'Java',
            evidenceState: 'DYNAMIC',
            children: [
              { id: 'code-method-retry', kind: 'METHOD', label: 'retry(paymentId)', path: 'PaymentTimeoutJob.retry', language: 'Java', evidenceState: 'DYNAMIC', children: [] },
            ],
          },
        ],
      },
    ],
  },
]

const traceabilityResponse = {
  baseline: {
    id: baseline.id,
    name: baseline.name,
    sourceAppId: baseline.sourceAppId,
    sourceAssetId: baseline.sourceAssetId,
    executionAssetId: baseline.executionAssetId,
    coverageAssetId: baseline.coverageAssetId,
    repositoryUrl: baseline.repositoryUrl,
    sourceBranch: baseline.sourceBranch,
    sourceCommit: baseline.sourceCommit,
    analyzerVersion: baseline.analyzerVersion,
    freshness: baseline.freshness,
    updateTime: baseline.updateTime,
  },
  summary: {
    requirementCount: 2,
    testcaseCount: 3,
    codeCount: 7,
    codeFileCount: 2,
    codeClassCount: 2,
    codeMethodCount: 3,
    completeChainCount: 2,
    completeChainRate: 0.67,
    staticNodeCount: 9,
    dynamicNodeCount: 6,
    brokenRequirementCount: 0,
    brokenTestcaseCount: 1,
    dynamicEvidenceCount: 3,
    staticBridgeCount: 2,
    clipped: false,
  },
  nodes: traceabilityNodes,
  edges: traceabilityEdges,
  codeTree: traceabilityCodeTree,
  codeGraph: null,
  coverageOverview: {
    coveredClasses: 2,
    totalClasses: 2,
    coveredMethods: 3,
    totalMethods: 3,
    coveredBranches: 15,
    totalBranches: 22,
    coveredLines: 96,
    totalLines: 122,
    coveredComplexity: 18,
    totalComplexity: 25,
  },
  warnings: [],
}

const graphNodes = [
  graphNode('n-req', 'REQUIREMENT', '支付成功需求', 'REQ-PAY-001'),
  graphNode('n-ac1', 'ACCEPTANCE_CRITERION', 'AC-01 支付成功返回', 'REQ-PAY-001#AC-01'),
  graphNode('n-tc1', 'TESTCASE', 'TC-PAY-001 正常支付成功', 'cases.xlsx#12'),
  graphNode('n-method1', 'METHOD', 'PaymentService.pay()', 'src/main/java/PaymentService.java:76'),
  graphNode('n-method2', 'METHOD', 'LedgerRepository.insert()', 'src/main/java/LedgerRepository.java:41'),
  graphNode('n-run1', 'TEST_EXECUTION', '接口自动化执行 #20260725', 'newman-report.json'),
  graphNode('n-cov1', 'COVERAGE_UNIT', 'PaymentService 行覆盖 88%', 'jacoco.xml#PaymentService'),
]
const graphEdges = [
  graphEdge('e1', 'n-req', 'n-ac1', 'HAS_AC'),
  graphEdge('e2', 'n-ac1', 'n-tc1', 'VERIFIED_BY'),
  graphEdge('e3', 'n-tc1', 'n-run1', 'EXECUTED_AS'),
  graphEdge('e4', 'n-tc1', 'n-method1', 'EXERCISES'),
  graphEdge('e5', 'n-method1', 'n-method2', 'CALLS_STATIC'),
  graphEdge('e6', 'n-cov1', 'n-method1', 'COVERED'),
]

const gateResult = {
  id: 'gate-1',
  projectId,
  baselineId,
  policyId: 'policy-1',
  verdict: 'FAILED',
  failures: [
    { ruleId: 'HIGH_FINDINGS', description: '存在未审核高风险发现', actualValue: '1 条', threshold: '0 条' },
    { ruleId: 'CHANGE_IMPACT', description: '存在未审核的变更影响发现', actualValue: '3 条', threshold: '0 条' },
    { ruleId: 'BRANCH_COVERAGE', description: '补偿任务分支覆盖率低于发布阈值', actualValue: '71.5%', threshold: '≥ 80%' },
  ],
  activeExemptions: [
    { id: 'ex-1', projectId, baselineId, ruleId: 'BRANCH_COVERAGE', reason: '补偿任务异常分支需夜间压测补齐，已纳入发布后验证计划。', grantedBy: 'qa.lead', createTime: now },
  ],
  metrics,
  evaluatedBy: 'qa.lead',
  evaluatedAt: now,
}

const gitJob = {
  jobId: 'git-job-1',
  projectId,
  baselineId,
  status: 'COMPLETED',
  stage: 'DONE',
  percent: 100,
  message: 'Git 影响分析完成',
  createdAt: now,
  updatedAt: now,
  result: {
    report: {
      id: 'git-report-1',
      changeSet: {
        repositoryUrl: baseline.repositoryUrl,
        baseCommit: '31a0c21',
        headCommit: '9f42a7c',
        files: [
          { oldPath: 'PaymentService.java', newPath: 'PaymentService.java', changeType: 'MODIFY', language: 'Java', newRanges: [{ startLine: 70, endLine: 96 }] },
          { oldPath: 'PaymentTimeoutJob.java', newPath: 'PaymentTimeoutJob.java', changeType: 'MODIFY', language: 'Java', newRanges: [{ startLine: 32, endLine: 58 }] },
        ],
      },
      directChanges: [
        { symbolKey: 'PaymentService#pay', changeType: 'MODIFY', facets: ['METHOD_BODY', 'RETURN_TYPE'], confidence: 0.92, newSymbol: { key: 'PaymentService#pay', kind: 'METHOD', language: 'Java', qualifiedName: 'PaymentService.pay', path: 'src/main/java/com/oat/payment/PaymentService.java', range: { startLine: 70, endLine: 96 } }, evidenceRanges: [{ startLine: 70, endLine: 96 }] },
        { symbolKey: 'PaymentTimeoutJob#retry', changeType: 'MODIFY', facets: ['METHOD_BODY', 'CONTROL_FLOW'], confidence: 0.89, newSymbol: { key: 'PaymentTimeoutJob#retry', kind: 'METHOD', language: 'Java', qualifiedName: 'PaymentTimeoutJob.retry', path: 'src/main/java/com/oat/payment/PaymentTimeoutJob.java', range: { startLine: 32, endLine: 58 } }, evidenceRanges: [{ startLine: 32, endLine: 58 }] },
        { symbolKey: 'PaymentResult#retryable', changeType: 'ADD', facets: ['FIELD'], confidence: 0.95, newSymbol: { key: 'PaymentResult#retryable', kind: 'FIELD', language: 'Java', qualifiedName: 'PaymentResult.retryable', path: 'src/main/java/com/oat/payment/api/PaymentResult.java', range: { startLine: 18, endLine: 18 } }, evidenceRanges: [{ startLine: 18, endLine: 18 }] },
      ],
      candidates: [
        { seedSymbol: 'PaymentService#pay', targetSymbol: 'REQ-PAY-001#AC-01', direction: 'UPSTREAM', distance: 1, classification: 'REQUIREMENT_IMPACT', ruleScore: 0.88, confidence: 0.86, reason: '支付入口逻辑修改影响成功返回验收标准', path: { symbols: ['PaymentService#pay', 'REQ-PAY-001#AC-01'], edgeTypes: ['IMPLEMENTED_BY'] } },
        { seedSymbol: 'PaymentService#pay', targetSymbol: 'TC-PAY-001', direction: 'UPSTREAM', distance: 2, classification: 'TESTCASE_IMPACT', ruleScore: 0.84, confidence: 0.83, reason: '支付响应字段变更需要复核正常支付断言', path: { symbols: ['PaymentService#pay', 'REQ-PAY-001#AC-01', 'TC-PAY-001'], edgeTypes: ['IMPLEMENTED_BY', 'VERIFIED_BY'] } },
        { seedSymbol: 'PaymentTimeoutJob#retry', targetSymbol: 'TC-PAY-021', direction: 'DOWNSTREAM', distance: 2, classification: 'TESTCASE_IMPACT', ruleScore: 0.82, confidence: 0.8, reason: '补偿任务修改影响超时补偿用例', path: { symbols: ['PaymentTimeoutJob#retry', 'REQ-PAY-002#AC-03', 'TC-PAY-021'], edgeTypes: ['IMPLEMENTED_BY', 'VERIFIED_BY'] } },
        { seedSymbol: 'PaymentResult#retryable', targetSymbol: 'REQ-PAY-002#AC-03', direction: 'UPSTREAM', distance: 1, classification: 'REQUIREMENT_IMPACT', ruleScore: 0.76, confidence: 0.78, reason: '新增重试标记影响补偿状态可追踪性', path: { symbols: ['PaymentResult#retryable', 'REQ-PAY-002#AC-03'], edgeTypes: ['IMPLEMENTED_BY'] } },
      ],
      llmJudgements: [
        { candidateId: 'c-1', decision: 'CONFIRM', confidence: 0.88, businessReason: '支付结果返回语义发生变化，需复核断言。', riskLevel: 'HIGH', recommendedTests: ['TC-PAY-001', 'TC-PAY-021'] },
        { candidateId: 'c-2', decision: 'CONFIRM', confidence: 0.81, businessReason: '补偿任务控制流修改，建议回归超时与重试场景。', riskLevel: 'MEDIUM', recommendedTests: ['TC-PAY-021'] },
      ],
    },
    traceability: { affectedSymbols: ['PaymentService#pay'], affectedCriteria: [criteria[0], criteria[2]], affectedTestcases: [testcases[0], testcases[2]] },
  },
}

const server = createServer((req, res) => {
  const url = new URL(req.url || '/', 'http://localhost:8899')
  const path = url.pathname

  if (path.startsWith('/api/projects/demo/map/graph')) {
    raw(res, graphResponse(path))
    return
  }
  if (path === '/api/projects/demo/map/traceability') return raw(res, traceabilityResponse)

  if (path === '/api/auth/me') return ok(res, user)
  if (path === '/api/projects') return ok(res, [project])
  if (path === '/api/projects/demo/context') return ok(res, { currentUser: user, project, apps, currentUserRole: 'OWNER', onlineAppCount: 1, appCount: apps.length })
  if (path === '/api/projects/demo/apps') return ok(res, apps)
  if (path === '/api/projects/demo/verification/overview') return ok(res, overview)
  if (path === `/api/projects/demo/verification/baselines/${baselineId}`) return ok(res, { baseline, requirementAsset: assets.requirement, testcaseAsset: assets.testcase, criteria, testcases, traceLinks, findings, metrics })
  if (path === `/api/projects/demo/verification/baselines/${baselineId}/matrix`) return ok(res, criteria.map((criterion, index) => ({ criterion, testcases: [testcases[index] || testcases[0]], codeLinks: [], findings: findings.filter((item) => item.acId === criterion.id), verdict: index === 2 ? 'PARTIAL' : 'SATISFIED', evidenceLevel: index === 2 ? 'E2' : 'E3' })))
  if (path === `/api/projects/demo/verification/baselines/${baselineId}/writebacks`) return ok(res, [])
  if (path === `/api/projects/demo/verification/baselines/${baselineId}/analysis-jobs/latest`) return ok(res, { id: 'job-1', projectId, baselineId, status: 'SUCCEEDED', message: 'AI 分析完成，等待复核', createTime: now, updateTime: now, finishTime: now })
  if (path === '/api/projects/demo/verification/quality-gate/policies') return ok(res, [{ id: 'policy-1', projectId, name: '发布前强门禁', minTestcaseCoverageRate: 0.9, minImplementationCoverageRate: 0.8, maxCriticalFindings: 0, maxHighFindings: 0, requireAllAmbiguitiesResolved: true, requireChangeImpactVerified: true, blockOnStaleBaseline: true, createTime: now, updateTime: now }])
  if (path === `/api/projects/demo/verification/baselines/${baselineId}/quality-gate/results`) return ok(res, [gateResult])
  if (path === `/api/projects/demo/verification/baselines/${baselineId}/quality-gate/exemptions`) return ok(res, gateResult.activeExemptions)
  if (path === `/api/projects/demo/verification/baselines/${baselineId}/git-change-impact-jobs`) return ok(res, gitJob)
  if (path === `/api/projects/demo/verification/baselines/${baselineId}/git-change-impact-jobs/start`) return ok(res, gitJob)
  if (path === '/api/projects/demo/verification/git-change-impact-jobs/git-job-1') return ok(res, gitJob)
  if (path === '/api/projects/demo/verification/git-change-impact/git-report-1/llm-review') return ok(res, { reportId: 'git-report-1', status: 'COMPLETED', total: 1, completed: 1, judgements: gitJob.result.report.llmJudgements })

  ok(res, null)
})

mkdirSync(outputDir, { recursive: true })
await listen(server, 8899)
const vite = spawn('npm', ['run', 'dev', '--', '--host', '127.0.0.1'], { cwd: frontendDir, stdio: 'pipe' })
try {
  await waitFor('http://127.0.0.1:5176/projects')
  if (process.env.OAT_KEEP_ALIVE === '1') {
    await new Promise(() => {})
  }
  const shots = [
    ['workflow-01-baseline.png', `http://127.0.0.1:5176/p/${projectId}/verification?workspace=baseline`],
    ['workflow-02-ai-verification.png', `http://127.0.0.1:5176/p/${projectId}/verification?workspace=result&baselineId=${baselineId}`],
    ['workflow-03-trace-map.png', `http://127.0.0.1:5176/p/${projectId}/map/home?docExpandInfo=1&docSelectIds=req-pay-001,tc-pay-001,code-method-pay`],
    ['workflow-04-git-impact.png', `http://127.0.0.1:5176/p/${projectId}/git-impact?docAutoRun=1&baselineId=${baselineId}&appId=${apps[0].id}&baseCommit=31a0c21&headCommit=9f42a7c`],
    ['workflow-05-quality-gate.png', `http://127.0.0.1:5176/p/${projectId}/verification/baselines/${baselineId}/gate`],
  ]
  for (const [file, url] of shots) {
    await screenshot(url, resolve(outputDir, file))
  }
} finally {
  vite.kill('SIGTERM')
  server.close()
}

function asset(id, assetType, fileName, contentPreview) {
  return {
    id,
    projectId,
    assetType,
    sourceType: 'FILE',
    fileName,
    contentHash: `${id}-hash`,
    contentSize: contentPreview.length,
    contentPreview,
    freshness: 'LIVE',
    capturedAt: now,
  }
}

function link(id, sourceType, sourceId, targetType, targetId, relationType, confidence) {
  return { id, sourceType, sourceId, targetType, targetId, relationType, generationMethod: 'AI_GENERATED', confidence, evidenceLevel: 'E3', reviewStatus: 'PENDING' }
}

function graphNode(id, kind, displayName, locator) {
  return { id, snapshotId: 'snap-1', baselineId, projectId, kind, locator, displayName, attributes: {} }
}

function graphEdge(id, sourceNodeId, targetNodeId, type) {
  return { id, snapshotId: 'snap-1', baselineId, projectId, sourceNodeId, targetNodeId, type, evidenceKind: 'DERIVED', evidenceLevel: 'E3', confidence: 0.86 }
}

function traceabilityNode(id, kind, label, description, layer, locator, evidenceState, metadata = {}, parentId, coverage) {
  return { id, kind, label, description, locator, layer, evidenceState, metadata, parentId, coverage }
}

function traceabilityEdge(id, source, target, relation, evidenceType, callEvidence, confidence) {
  return {
    id,
    source,
    target,
    relation,
    direction: 'FORWARD',
    evidenceType,
    callEvidence,
    confidence,
    evidenceLevel: 'E3',
    generationMethod: 'DOC_MOCK',
    reviewStatus: 'PENDING',
    evidence: [],
  }
}

function graphResponse(path) {
  if (path.endsWith('/focus-candidates')) return graphNodes
  return {
    snapshot: { id: 'snap-1', projectId, baselineId, kind: 'TRACEABILITY', analyzerVersion: 'demo', inputHash: 'demo', status: 'READY' },
    nodes: graphNodes,
    edges: graphEdges,
    nodesClipped: false,
    edgesClipped: false,
    depth: 3,
    maxNodes: 200,
    maxEdges: 300,
    summary: {
      staticReady: true,
      runtimeReady: true,
      runtimeTraceReady: true,
      traceabilityReady: true,
      fusionState: 'EXECUTED_CONFIRMED',
      snapshots: [],
      projectionStats: { TRACEABILITY: { nodeCount: graphNodes.length, edgeCount: graphEdges.length } },
    },
    clipReasons: [],
    totalActiveNodes: graphNodes.length,
    edgesInScope: graphEdges.length,
  }
}

function ok(res, data) {
  raw(res, { result: true, success: true, data })
}

function raw(res, data) {
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' })
  res.end(JSON.stringify(data))
}

function listen(instance, port) {
  return new Promise((resolveListen) => instance.listen(port, resolveListen))
}

async function waitFor(url) {
  for (let index = 0; index < 60; index += 1) {
    try {
      const response = await fetch(url)
      if (response.ok) return
    } catch {}
    await new Promise((resolveWait) => setTimeout(resolveWait, 500))
  }
  throw new Error(`Timed out waiting for ${url}`)
}

function screenshot(url, output) {
  return new Promise((resolveShot, reject) => {
    const chrome = spawn(chromePath, [
      '--headless=new',
      '--disable-gpu',
      '--hide-scrollbars',
      '--window-size=1440,1100',
      '--force-device-scale-factor=1',
      '--virtual-time-budget=12000',
      `--screenshot=${output}`,
      url,
    ], { stdio: 'ignore' })
    chrome.on('exit', (code) => code === 0 ? resolveShot() : reject(new Error(`Chrome exited with ${code} for ${url}`)))
    chrome.on('error', reject)
  })
}
