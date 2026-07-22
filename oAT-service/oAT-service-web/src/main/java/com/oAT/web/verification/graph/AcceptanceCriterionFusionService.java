package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.EvidenceLevel;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import com.oAT.web.verification.model.VerificationModels.Verdict;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AcceptanceCriterionFusionService {
    public static final String AGGREGATE_KIND = "AC_FUSION";
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;

    public AcceptanceCriterionFusionService(GraphRepository graphRepository, VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
    }

    public List<FusionResult> rebuild(String projectId, String baselineId) {
        List<AcceptanceCriterion> criteria = verificationRepository.findCriteria(baselineId);
        List<TestcaseProjection> testcases = verificationRepository.findTestcases(baselineId);
        List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
        graphRepository.invalidateAggregates(baselineId, AGGREGATE_KIND);
        List<FusionResult> results = new ArrayList<>();
        for (AcceptanceCriterion criterion : criteria) {
            List<TraceLink> evidence = links.stream().filter(link -> criterion.id().equals(link.sourceId())).toList();
            boolean testcase = hasTarget(evidence, "TESTCASE");
            boolean implementation = hasTarget(evidence, "SOURCE_SYMBOL");
            boolean execution = hasTarget(evidence, "EXECUTION") || hasTraceForLinkedTestcases(evidence, testcases, baselineId);
            boolean coverage = hasMethodCoverageForImplementation(evidence, baselineId);
            EvidenceLevel level = coverage ? EvidenceLevel.E4 : execution ? EvidenceLevel.E3
                    : implementation ? EvidenceLevel.E2 : testcase ? EvidenceLevel.E1 : EvidenceLevel.E0;
            Verdict verdict = testcase && implementation && (execution || coverage) ? Verdict.SATISFIED
                    : testcase && implementation ? Verdict.STATICALLY_CONSISTENT
                    : testcase || implementation ? Verdict.PARTIAL : Verdict.NOT_VERIFIABLE;
            List<String> gaps = gaps(testcase, implementation, execution, coverage);
            double confidence = confidence(evidence, testcase, implementation, execution, coverage);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("acKey", criterion.acKey());
            payload.put("requirementKey", criterion.requirementKey());
            payload.put("testcaseLinked", testcase);
            payload.put("implementationLinked", implementation);
            payload.put("executionLinked", execution);
            payload.put("coverageLinked", coverage);
            payload.put("evidenceLevel", level.name());
            payload.put("verdict", verdict.name());
            payload.put("confidence", confidence);
            payload.put("gaps", gaps);
            payload.put("evidenceLinkCount", evidence.size());
            String sourceHash = GraphModels.fingerprint(criterion.id() + "|" + evidence.stream().map(TraceLink::id).sorted().reduce("", String::concat));
            graphRepository.saveAggregate(new GraphRepository.GraphAggregate(
                    "ac-fusion:" + GraphModels.fingerprint(baselineId + "|" + criterion.id() + "|" + sourceHash),
                    baselineId, projectId, AGGREGATE_KIND, criterion.id(), sourceHash, payload));
            results.add(new FusionResult(criterion.id(), criterion.acKey(), verdict, level, confidence, gaps));
        }
        return results;
    }

    public List<GraphRepository.GraphAggregate> results(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        return graphRepository.findActiveAggregates(baselineId, AGGREGATE_KIND);
    }

    private boolean hasTraceForLinkedTestcases(List<TraceLink> evidence, List<TestcaseProjection> testcases, String baselineId) {
        return evidence.stream().filter(link -> "TESTCASE".equals(link.targetType()))
                .map(TraceLink::targetId)
                .flatMap(id -> testcases.stream().filter(testcase -> id.equals(testcase.id())).map(TestcaseProjection::externalKey))
                .anyMatch(key -> graphRepository.hasRuntimeTraceForTestcase(baselineId, key));
    }

    private boolean hasMethodCoverageForImplementation(List<TraceLink> evidence, String baselineId) {
        return evidence.stream().filter(link -> "SOURCE_SYMBOL".equals(link.targetType()))
                .map(TraceLink::targetId)
                .anyMatch(symbol -> graphRepository.hasMethodCoverageForSymbol(baselineId, symbol));
    }

    private boolean hasTarget(List<TraceLink> links, String type) {
        return links.stream().anyMatch(link -> type.equals(link.targetType()));
    }

    private List<String> gaps(boolean testcase, boolean implementation, boolean execution, boolean coverage) {
        List<String> result = new ArrayList<>();
        if (!testcase) result.add("MISSING_TESTCASE");
        if (!implementation) result.add("MISSING_IMPLEMENTATION_EVIDENCE");
        if (!execution) result.add("MISSING_EXECUTION_EVIDENCE");
        if (!coverage) result.add("MISSING_COVERAGE_EVIDENCE");
        return result;
    }

    private double confidence(List<TraceLink> evidence, boolean testcase, boolean implementation, boolean execution, boolean coverage) {
        double linkConfidence = evidence.stream().mapToDouble(TraceLink::confidence).average().orElse(0d);
        double completeness = (testcase ? .2 : 0) + (implementation ? .3 : 0) + (execution ? .25 : 0) + (coverage ? .25 : 0);
        return Math.round(Math.min(1d, linkConfidence * .5 + completeness * .5) * 10_000d) / 10_000d;
    }

    public record FusionResult(String criterionId, String acKey, Verdict verdict, EvidenceLevel evidenceLevel,
                               double confidence, List<String> gaps) {}
}
