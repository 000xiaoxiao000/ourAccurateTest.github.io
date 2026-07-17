package com.oAT.web.verification.impact;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ImpactTraceabilityMapper {
    private final VerificationRepository repository;

    public ImpactTraceabilityMapper(VerificationRepository repository) {
        this.repository = repository;
    }

    public TraceabilityImpact map(String baselineId, List<ImpactModels.ImpactCandidate> candidates) {
        Set<String> affectedSymbols = new HashSet<>();
        candidates.stream().filter(candidate -> candidate.confidence() >= .45d)
                .forEach(candidate -> affectedSymbols.add(candidate.targetSymbol()));
        List<TraceLink> links = repository.findTraceLinks(baselineId);
        Set<String> acIds = new HashSet<>();
        Set<String> testcaseIds = new HashSet<>();
        for (TraceLink link : links) {
            if ("SOURCE_SYMBOL".equals(link.targetType()) && affectedSymbols.contains(link.targetId())) {
                acIds.add(link.sourceId());
            }
        }
        for (TraceLink link : links) {
            if (acIds.contains(link.sourceId()) && "TESTCASE".equals(link.targetType())) {
                testcaseIds.add(link.targetId());
            }
        }
        List<AcceptanceCriterion> criteria = repository.findCriteria(baselineId).stream()
                .filter(ac -> acIds.contains(ac.id())).toList();
        List<TestcaseProjection> testcases = repository.findTestcases(baselineId).stream()
                .filter(tc -> testcaseIds.contains(tc.id())).toList();
        return new TraceabilityImpact(new ArrayList<>(affectedSymbols), criteria, testcases);
    }

    public record TraceabilityImpact(List<String> affectedSymbols, List<AcceptanceCriterion> affectedCriteria,
                                     List<TestcaseProjection> affectedTestcases) {
    }
}
