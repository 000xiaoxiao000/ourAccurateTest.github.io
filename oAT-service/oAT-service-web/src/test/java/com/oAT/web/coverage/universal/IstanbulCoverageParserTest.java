package com.oAT.web.coverage.universal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IstanbulCoverageParserTest {
    private final IstanbulCoverageParser parser = new IstanbulCoverageParser();

    @Test
    void parseNormalizesAbsoluteSourcePathAndKeepsBranches() {
        String payload = """
                {
                  "/Users/xiaoxiao/work-JavaProject/front-web/src/utils/index.js": {
                    "path": "/Users/xiaoxiao/work-JavaProject/front-web/src/utils/index.js",
                    "statementMap": {
                      "0": { "start": { "line": 1 }, "end": { "line": 1 } }
                    },
                    "s": { "0": 1 },
                    "fnMap": {
                      "0": { "name": "pick", "loc": { "start": { "line": 1 }, "end": { "line": 4 } } }
                    },
                    "f": { "0": 1 },
                    "branchMap": {
                      "0": {
                        "loc": { "start": { "line": 2 } },
                        "locations": [
                          { "start": { "line": 2 } },
                          { "start": { "line": 3 } }
                        ]
                      }
                    },
                    "b": { "0": [1, 0] }
                  }
                }
                """;

        List<UniversalCoverageFile> files = parser.parse(payload.getBytes(StandardCharsets.UTF_8));

        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFilePath()).isEqualTo("src/utils/index.js");
        assertThat(files.get(0).getBranches()).hasSize(2);
        assertThat(files.get(0).getBranches()).extracting(UniversalCoverageFile.BranchCoverage::getCoveredCount)
                .containsExactly(1, 0);
    }
}
