package com.oAT.web.verification.impact;

import com.oAT.web.verification.impact.ImpactModels.SymbolSnapshot;

import java.util.List;

public interface LanguageAnalyzer {
    boolean supports(String path);
    List<SymbolSnapshot> analyze(String path, String source);
}
