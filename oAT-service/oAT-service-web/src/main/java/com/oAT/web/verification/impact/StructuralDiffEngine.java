package com.oAT.web.verification.impact;

import com.oAT.web.verification.impact.ImpactModels.ChangeFacet;
import com.oAT.web.verification.impact.ImpactModels.SymbolChange;
import com.oAT.web.verification.impact.ImpactModels.SymbolChangeType;
import com.oAT.web.verification.impact.ImpactModels.SymbolSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StructuralDiffEngine {
    public List<SymbolChange> diff(List<SymbolSnapshot> oldSymbols, List<SymbolSnapshot> newSymbols) {
        Map<String, SymbolSnapshot> oldByKey = index(oldSymbols);
        Map<String, SymbolSnapshot> newByKey = index(newSymbols);
        List<SymbolChange> changes = new ArrayList<>();
        for (Map.Entry<String, SymbolSnapshot> entry : oldByKey.entrySet()) {
            SymbolSnapshot current = newByKey.remove(entry.getKey());
            if (current == null) changes.add(new SymbolChange(entry.getKey(), null, SymbolChangeType.DELETE, List.of(ChangeFacet.BODY), entry.getValue(), null, List.of(entry.getValue().range())));
            else if (!entry.getValue().astHash().equals(current.astHash()) || !entry.getValue().apiHash().equals(current.apiHash())) {
                changes.add(new SymbolChange(entry.getKey(), current.key(), SymbolChangeType.MODIFY, facets(entry.getValue(), current), entry.getValue(), current, List.of(current.range())));
            }
        }
        for (SymbolSnapshot added : newByKey.values()) changes.add(new SymbolChange(null, added.key(), SymbolChangeType.ADD, List.of(ChangeFacet.BODY), null, added, List.of(added.range())));
        return changes;
    }

    private Map<String, SymbolSnapshot> index(List<SymbolSnapshot> symbols) { Map<String, SymbolSnapshot> indexed = new LinkedHashMap<>(); for (SymbolSnapshot symbol : symbols) indexed.put(symbol.key(), symbol); return indexed; }
    private List<ChangeFacet> facets(SymbolSnapshot oldSymbol, SymbolSnapshot newSymbol) {
        List<ChangeFacet> result = new ArrayList<>();
        if (!oldSymbol.apiHash().equals(newSymbol.apiHash())) { result.add(ChangeFacet.PARAMETER); result.add(ChangeFacet.RETURN_TYPE); }
        if (!oldSymbol.bodyHash().equals(newSymbol.bodyHash())) result.add(ChangeFacet.BODY);
        if (!oldSymbol.invokedNames().equals(newSymbol.invokedNames())) result.add(ChangeFacet.CALL);
        return result.isEmpty() ? List.of(ChangeFacet.BODY) : result;
    }
}
