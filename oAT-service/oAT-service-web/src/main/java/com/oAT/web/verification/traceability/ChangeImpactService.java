package com.oAT.web.verification.traceability;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyses change impact within a baseline and detects orphan artifacts.
 *
 * Change impact: given a changed AC key, walks the trace graph to surface
 * all downstream testcases, code symbols and findings that need re-verification.
 *
 * Orphan detection: surfaces AC with no testcase link, testcases with no AC,
 * and findings with no supporting trace link.
 */
@Service
public class ChangeImpactService {

    private final VerificationRepository repository;

    public ChangeImpactService(VerificationRepository repository) {
        this.repository = repository;
    }

    public ChangeImpactReport analyzeImpact(String projectId, String baselineId,
                                             String changeDescription, String userId) {
        List<AcceptanceCriterion> criteria = repository.findCriteria(baselineId);
        List<TestcaseProjection> testcases = repository.findTestcases(baselineId);
        List<TraceLink> links = repository.findTraceLinks(baselineId);
        List<Finding> findings = repository.findFindings(baselineId);

        // Index links by source AC id
        Map<String, List<TraceLink>> linksByAc = links.stream()
                .collect(Collectors.groupingBy(TraceLink::sourceId));

        // Determine impacted ACs: those with findings that are NOT_SATISFIED, AMBIGUOUS or PARTIAL
        Set<String> riskyAcIds = findings.stream()
                .filter(f -> f.verdict() == Verdict.NOT_SATISFIED
                        || f.verdict() == Verdict.AMBIGUOUS
                        || f.verdict() == Verdict.PARTIAL
                        || f.reviewStatus() == ReviewStatus.STALE)
                .map(Finding::acId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<ImpactedAc> impactedCriteria = criteria.stream()
                .filter(ac -> riskyAcIds.contains(ac.id()))
                .map(ac -> buildImpactedAc(ac, linksByAc.getOrDefault(ac.id(), List.of()), testcases))
                .toList();

        // Impacted testcases: those linked from impacted ACs
        Set<String> impactedTcIds = impactedCriteria.stream()
                .flatMap(iac -> iac.affectedTestcaseIds().stream())
                .collect(Collectors.toSet());

        List<ImpactedTestcase> impactedTestcases = testcases.stream()
                .filter(tc -> impactedTcIds.contains(tc.id()))
                .map(tc -> new ImpactedTestcase(tc.id(), tc.externalKey(), tc.title(),
                        ImpactLevel.HIGH, "关联验收标准存在未满足或模糊问题"))
                .toList();

        // Orphan detection
        List<OrphanItem> orphans = detectOrphans(criteria, testcases, links, findings);

        return new ChangeImpactReport(UUID.randomUUID().toString(), projectId, baselineId,
                StringUtils.hasText(changeDescription) ? changeDescription : "变更影响分析",
                impactedCriteria, impactedTestcases, orphans, userId, LocalDateTime.now());
    }

    private ImpactedAc buildImpactedAc(AcceptanceCriterion ac, List<TraceLink> acLinks,
                                        List<TestcaseProjection> allTestcases) {
        Set<String> testcaseIds = acLinks.stream()
                .filter(l -> "TESTCASE".equals(l.targetType()))
                .map(TraceLink::targetId)
                .collect(Collectors.toSet());

        List<String> symbolIds = acLinks.stream()
                .filter(l -> "SOURCE_SYMBOL".equals(l.targetType()))
                .map(TraceLink::targetId)
                .toList();

        ImpactLevel level = testcaseIds.isEmpty() && symbolIds.isEmpty()
                ? ImpactLevel.HIGH : ImpactLevel.MEDIUM;

        return new ImpactedAc(ac.id(), ac.requirementKey(), ac.acKey(), ac.title(), level,
                "需求变更或问题未满足，关联测试和实现需要重新验证",
                new ArrayList<>(testcaseIds), symbolIds);
    }

    private List<OrphanItem> detectOrphans(List<AcceptanceCriterion> criteria,
                                            List<TestcaseProjection> testcases,
                                            List<TraceLink> links,
                                            List<Finding> findings) {
        Set<String> linkedAcIds = links.stream().map(TraceLink::sourceId).collect(Collectors.toSet());
        Set<String> linkedTcIds = links.stream()
                .filter(l -> "TESTCASE".equals(l.targetType()))
                .map(TraceLink::targetId)
                .collect(Collectors.toSet());

        List<OrphanItem> orphans = new ArrayList<>();

        // ACs with no trace link at all
        for (AcceptanceCriterion ac : criteria) {
            if (!linkedAcIds.contains(ac.id())) {
                orphans.add(new OrphanItem(ac.id(), OrphanType.NO_TESTCASE, "AC",
                        ac.requirementKey() + " / " + ac.acKey(),
                        "该验收标准没有任何追溯关系，缺少测试用例或代码依据"));
            }
        }

        // Testcases with no AC back-link
        Set<String> acIdsFromLinks = links.stream()
                .filter(l -> "TESTCASE".equals(l.targetType()))
                .map(TraceLink::sourceId)
                .collect(Collectors.toSet());

        for (TestcaseProjection tc : testcases) {
            boolean hasRequirementRef = StringUtils.hasText(tc.requirementRefs());
            boolean hasLink = linkedTcIds.contains(tc.id());
            if (!hasLink && !hasRequirementRef) {
                orphans.add(new OrphanItem(tc.id(), OrphanType.NO_REQUIREMENT, "TESTCASE",
                        tc.title(), "该测试用例没有关联到任何验收标准"));
            }
        }

        return orphans;
    }
}
