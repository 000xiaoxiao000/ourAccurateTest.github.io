package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Assertion vs AC semantic consistency. The plan requires that "executed but the assertion does not match the
 * AC semantics = false pass". This service is deterministic and evidence-based: it never claims a semantic
 * match from thin air. For each AC linked to a testcase, it compares the AC's testable intent (keywords and
 * expected outcome terms) against the testcase's expected result / steps. Low term overlap on an AC that is
 * otherwise "executed" is flagged as a SUSPECTED_FALSE_PASS for human review, not auto-failed.
 */
@Service
public class AssertionConsistencyService {
    public static final String AGGREGATE_KIND = "ASSERTION_CONSISTENCY";
    private static final double WEAK_OVERLAP_THRESHOLD = 0.15d;
    private static final int MIN_AC_TERMS = 3;
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;

    public AssertionConsistencyService(GraphRepository graphRepository, VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
    }

    public List<ConsistencyResult> evaluate(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        List<AcceptanceCriterion> criteria = verificationRepository.findCriteria(baselineId);
        List<TestcaseProjection> testcases = verificationRepository.findTestcases(baselineId);
        List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
        graphRepository.invalidateAggregates(baselineId, AGGREGATE_KIND);

        Map<String, TestcaseProjection> testcaseById = new LinkedHashMap<>();
        for (TestcaseProjection testcase : testcases) {
            testcaseById.put(testcase.id(), testcase);
            if (StringUtils.hasText(testcase.externalKey())) testcaseById.put(testcase.externalKey(), testcase);
        }

        List<ConsistencyResult> results = new ArrayList<>();
        for (AcceptanceCriterion criterion : criteria) {
            List<TraceLink> evidence = links.stream().filter(link -> criterion.id().equals(link.sourceId())).toList();
            List<TestcaseProjection> linkedTestcases = evidence.stream()
                    .filter(link -> "TESTCASE".equals(link.targetType()))
                    .map(link -> testcaseById.get(link.targetId()))
                    .filter(item -> item != null).toList();
            if (linkedTestcases.isEmpty()) continue;

            Set<String> acTerms = terms(criterion.title() + " " + criterion.content());
            if (acTerms.size() < MIN_AC_TERMS) continue;

            double bestOverlap = 0d;
            String bestTestcase = null;
            for (TestcaseProjection testcase : linkedTestcases) {
                Set<String> assertionTerms = terms(value(testcase.expected()) + " " + value(testcase.steps()) + " " + value(testcase.title()));
                double overlap = jaccard(acTerms, assertionTerms);
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    bestTestcase = testcase.externalKey();
                }
            }

            boolean weak = bestOverlap < WEAK_OVERLAP_THRESHOLD;
            String verdict = weak ? "SUSPECTED_FALSE_PASS" : "ASSERTION_ALIGNED";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("acKey", criterion.acKey());
            payload.put("requirementKey", criterion.requirementKey());
            payload.put("linkedTestcaseCount", linkedTestcases.size());
            payload.put("bestAssertionOverlap", round(bestOverlap));
            payload.put("bestTestcaseKey", bestTestcase);
            payload.put("verdict", verdict);
            payload.put("reason", weak
                    ? "该 AC 已被用例关联，但用例预期与 AC 语义词项重合极低，可能是假通过，请人工确认断言是否真正验证该 AC"
                    : "用例预期与 AC 语义存在合理重合");
            String sourceHash = GraphModels.fingerprint(criterion.id() + "|" + round(bestOverlap) + "|" + linkedTestcases.size());
            graphRepository.saveAggregate(new GraphRepository.GraphAggregate(
                    "assertion:" + GraphModels.fingerprint(baselineId + "|" + criterion.id() + "|" + sourceHash),
                    baselineId, projectId, AGGREGATE_KIND, criterion.id(), sourceHash, payload));
            results.add(new ConsistencyResult(criterion.id(), criterion.acKey(), verdict, round(bestOverlap), bestTestcase));
        }
        return results;
    }

    public List<GraphRepository.GraphAggregate> results(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        return graphRepository.findActiveAggregates(baselineId, AGGREGATE_KIND);
    }

    private Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) return result;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}\\u4e00-\\u9fa5]+")) {
            if (token.length() >= 2 && !STOPWORDS.contains(token)) result.add(token);
        }
        return result;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0d;
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0d : (double) intersection.size() / union.size();
    }

    private double round(double value) { return Math.round(value * 10_000d) / 10_000d; }
    private String value(String text) { return text == null ? "" : text; }

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "should", "when", "then", "given", "will", "must",
            "是", "的", "了", "在", "和", "与", "对", "为", "如果", "那么", "应该", "验证", "测试", "用例");

    public record ConsistencyResult(String criterionId, String acKey, String verdict, double assertionOverlap, String bestTestcaseKey) {}
}
