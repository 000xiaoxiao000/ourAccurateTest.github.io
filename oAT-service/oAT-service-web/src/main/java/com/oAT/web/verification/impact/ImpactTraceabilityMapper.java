package com.oAT.web.verification.impact;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ImpactTraceabilityMapper {
    private final VerificationRepository repository;

    public ImpactTraceabilityMapper(VerificationRepository repository) {
        this.repository = repository;
    }

    public TraceabilityImpact map(String baselineId, List<ImpactModels.ImpactCandidate> candidates) {
        Set<String> affectedSymbols = new HashSet<>();
        Set<String> affectedAliases = new HashSet<>();
        candidates.stream().filter(candidate -> candidate.confidence() >= .45d)
                .forEach(candidate -> {
                    affectedSymbols.add(candidate.targetSymbol());
                    affectedAliases.addAll(symbolAliases(candidate.targetSymbol()));
                });
        List<TraceLink> links = repository.findTraceLinks(baselineId);
        Set<String> acIds = new HashSet<>();
        Set<String> testcaseIds = new HashSet<>();
        for (TraceLink link : links) {
            if ("SOURCE_SYMBOL".equals(link.targetType()) && symbolMatches(link.targetId(), affectedAliases)) {
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

    private boolean symbolMatches(String traceTarget, Set<String> affectedAliases) {
        if (!StringUtils.hasText(traceTarget)) {
            return false;
        }
        for (String alias : symbolAliases(traceTarget)) {
            if (affectedAliases.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> symbolAliases(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (!StringUtils.hasText(value)) {
            return result;
        }
        String cleaned = value.trim().replace('\\', '/').replace("java://", "");
        addAlias(result, cleaned);
        String owner = cleaned;
        String member = "";
        int hash = cleaned.indexOf('#');
        if (hash >= 0) {
            owner = cleaned.substring(0, hash);
            member = cleaned.substring(hash + 1);
            addAlias(result, owner + "#" + member);
            addAlias(result, owner + "." + member);
            String simpleOwner = simpleName(owner);
            addAlias(result, simpleOwner + "#" + member);
            addAlias(result, simpleOwner + "." + member);
            String memberName = memberName(member);
            addAlias(result, owner + "#" + memberName);
            addAlias(result, owner + "." + memberName);
            addAlias(result, simpleOwner + "#" + memberName);
            addAlias(result, simpleOwner + "." + memberName);
            addAlias(result, memberName);
        } else {
            addAlias(result, simpleName(owner));
        }
        return result;
    }

    private String simpleName(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.replace('/', '.');
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private String memberName(String member) {
        if (!StringUtils.hasText(member)) {
            return "";
        }
        int paren = member.indexOf('(');
        return paren >= 0 ? member.substring(0, paren) : member;
    }

    private void addAlias(Set<String> aliases, String value) {
        String normalized = normalize(value);
        if (StringUtils.hasText(normalized) && normalized.length() >= 3) {
            aliases.add(normalized);
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .replace("SOURCE_ASSET:", "")
                .replace('\\', '/')
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    public record TraceabilityImpact(List<String> affectedSymbols, List<AcceptanceCriterion> affectedCriteria,
                                     List<TestcaseProjection> affectedTestcases) {
    }
}
