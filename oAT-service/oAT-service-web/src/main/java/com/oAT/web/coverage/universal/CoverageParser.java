package com.oAT.web.coverage.universal;

import java.util.List;

public interface CoverageParser {
    SourceType sourceType();

    List<UniversalCoverageFile> parse(byte[] payload);
}
