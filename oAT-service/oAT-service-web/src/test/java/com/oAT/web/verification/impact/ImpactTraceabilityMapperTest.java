package com.oAT.web.verification.impact;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.impact.ImpactModels.ImpactCandidate;
import com.oAT.web.verification.impact.ImpactModels.ImpactClassification;
import com.oAT.web.verification.impact.ImpactModels.ImpactDirection;
import com.oAT.web.verification.impact.ImpactModels.ImpactPath;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.EvidenceLevel;
import com.oAT.web.verification.model.VerificationModels.ReviewStatus;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImpactTraceabilityMapperTest {
    @Test
    void mapsJavaImpactSymbolsToHumanReadableSourceLinks() {
        VerificationRepository repository = mock(VerificationRepository.class);
        ImpactTraceabilityMapper mapper = new ImpactTraceabilityMapper(repository);
        String baselineId = "baseline-1";
        AcceptanceCriterion criterion = new AcceptanceCriterion("ac-1", baselineId, "REQ-1", "AC-1",
                "Web3 balance", "check web3 balance", null, "P1", true, false, .9d);
        TestcaseProjection testcase = new TestcaseProjection("tc-1", baselineId, "TC-1",
                "Web3 regression", null, null, null, null, "REQ-1", null);
        when(repository.findTraceLinks(baselineId)).thenReturn(List.of(
                link("l-1", baselineId, "AC", "ac-1", "SOURCE_SYMBOL", "Web3Controller#web3", "IMPLEMENTED_BY"),
                link("l-2", baselineId, "AC", "ac-1", "TESTCASE", "tc-1", "VERIFIED_BY")
        ));
        when(repository.findCriteria(baselineId)).thenReturn(List.of(criterion));
        when(repository.findTestcases(baselineId)).thenReturn(List.of(testcase));

        var result = mapper.map(baselineId, List.of(candidate("java://com.demo.Web3Controller#web3(Integer num1, Integer num2)")));

        assertEquals(List.of(criterion), result.affectedCriteria());
        assertEquals(List.of(testcase), result.affectedTestcases());
    }

    private ImpactCandidate candidate(String symbol) {
        return new ImpactCandidate(symbol, symbol, ImpactDirection.TRACEABILITY, 0, ImpactClassification.DIRECT,
                1d, null, 1d, "结构化变更", new ImpactPath(List.of(symbol), List.of(), 1d), Map.of());
    }

    private TraceLink link(String id, String baselineId, String sourceType, String sourceId,
                           String targetType, String targetId, String relationType) {
        return new TraceLink(id, baselineId, sourceType, sourceId, targetType, targetId, relationType,
                "TEST", .9d, EvidenceLevel.E1, ReviewStatus.PENDING, Map.of());
    }
}
