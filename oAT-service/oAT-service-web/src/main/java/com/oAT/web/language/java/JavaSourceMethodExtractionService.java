package com.oAT.web.language.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JavaSourceMethodExtractionService {

    public Map<String, MethodInfo> extractMethodsWithLines(String source) {
        Map<String, MethodInfo> result = new LinkedHashMap<>();
        if (!StringUtils.hasText(source)) {
            return result;
        }

        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        JavaParser parser = new JavaParser(configuration);
        ParseResult<CompilationUnit> parseResult = parser.parse(source);
        if (!parseResult.isSuccessful() || !parseResult.getResult().isPresent()) {
            return result;
        }

        CompilationUnit cu = parseResult.getResult().get();
        AstMethodCollector collector = new AstMethodCollector(source, result);
        collector.visit(cu, new ArrayDeque<>());
        return result;
    }

    public static class MethodInfo {
        public final String body;
        public final int startLine;
        public final int endLine;

        MethodInfo(String body, int startLine, int endLine) {
            this.body = body;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    private static class AstMethodCollector extends VoidVisitorAdapter<Deque<String>> {
        private final String source;
        private final Map<String, MethodInfo> methods;
        private final IdentityHashMap<Node, Integer> anonymousCounters = new IdentityHashMap<>();

        private AstMethodCollector(String source, Map<String, MethodInfo> methods) {
            this.source = source;
            this.methods = methods;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Deque<String> path) {
            visitNamedType(n, n.getNameAsString(), path);
        }

        @Override
        public void visit(EnumDeclaration n, Deque<String> path) {
            visitNamedType(n, n.getNameAsString(), path);
        }

        @Override
        public void visit(RecordDeclaration n, Deque<String> path) {
            visitNamedType(n, n.getNameAsString(), path);
        }

        @Override
        public void visit(MethodDeclaration n, Deque<String> path) {
            addCallableMethod(n, n.getNameAsString(), n.getBody().orElse(null), path);
            super.visit(n, path);
        }

        @Override
        public void visit(ConstructorDeclaration n, Deque<String> path) {
            addCallableMethod(n, n.getNameAsString(), n.getBody(), path);
            super.visit(n, path);
        }

        @Override
        public void visit(CompactConstructorDeclaration n, Deque<String> path) {
            addCompactConstructorMethod(n, path);
            super.visit(n, path);
        }

        @Override
        public void visit(ObjectCreationExpr n, Deque<String> path) {
            if (n.getAnonymousClassBody().isPresent()) {
                int nextIndex = anonymousCounters.merge(n.getParentNode().orElse(null), 1, Integer::sum);
                String anonName = "Anon" + nextIndex;
                path.addLast(anonName);
                try {
                    super.visit(n, path);
                } finally {
                    path.removeLast();
                }
                return;
            }
            super.visit(n, path);
        }

        @Override
        public void visit(BlockStmt n, Deque<String> path) {
            if (isInitializerBody(n)) {
                String initName = isStaticInitializer(n) ? "<clinit>" : "<init>_block";
                addBlockMethod(initName, n, path);
            }
            super.visit(n, path);
        }

        private void visitNamedType(TypeDeclaration<?> n, String name, Deque<String> path) {
            path.addLast(name);
            try {
                for (Node child : n.getChildNodes()) {
                    child.accept(this, path);
                }
            } finally {
                path.removeLast();
            }
        }

        private void addCallableMethod(CallableDeclaration<?> declaration, String name, Node bodyNode, Deque<String> path) {
            Range bodyRange = getBodyRange(bodyNode, declaration.getRange().orElse(null));
            if (bodyRange == null) {
                return;
            }
            String fullName = buildMethodName(path, name);
            methods.put(fullName, new MethodInfo(extractRangeText(bodyRange), bodyRange.begin.line, bodyRange.end.line));
        }

        private void addCompactConstructorMethod(CompactConstructorDeclaration declaration, Deque<String> path) {
            Range bodyRange = declaration.getBody().getRange().orElse(declaration.getRange().orElse(null));
            if (bodyRange == null) {
                return;
            }
            String fullName = buildMethodName(path, declaration.getNameAsString());
            methods.put(fullName, new MethodInfo(extractRangeText(bodyRange), bodyRange.begin.line, bodyRange.end.line));
        }

        private void addBlockMethod(String name, BlockStmt body, Deque<String> path) {
            Range range = body.getRange().orElse(null);
            if (range == null) {
                return;
            }
            String fullName = buildMethodName(path, name);
            methods.put(fullName, new MethodInfo(extractRangeText(range), range.begin.line, range.end.line));
        }

        private String buildMethodName(Deque<String> path, String methodName) {
            if (path.isEmpty()) {
                return methodName;
            }
            return String.join("$", path) + "." + methodName;
        }

        private Range getBodyRange(Node bodyNode, Range fallback) {
            if (bodyNode != null) {
                return bodyNode.getRange().orElse(fallback);
            }
            return fallback;
        }

        private String extractRangeText(Range range) {
            if (range == null) {
                return "";
            }
            int begin = positionToIndex(source, range.begin);
            int end = positionToIndexExclusive(source, range.end);
            if (begin < 0 || end < begin || begin > source.length()) {
                return "";
            }
            end = Math.min(end, source.length());
            return source.substring(begin, end).trim();
        }

        private boolean isInitializerBody(BlockStmt block) {
            Node parent = block.getParentNode().orElse(null);
            return parent instanceof InitializerDeclaration;
        }

        private boolean isStaticInitializer(BlockStmt block) {
            Node parent = block.getParentNode().orElse(null);
            if (parent instanceof InitializerDeclaration) {
                return ((InitializerDeclaration) parent).isStatic();
            }
            return false;
        }
    }

    private static int positionToIndex(String source, Position position) {
        if (position == null) {
            return -1;
        }
        int line = 1;
        int column = 1;
        for (int i = 0; i < source.length(); i++) {
            if (line == position.line && column == position.column) {
                return i;
            }
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        if (line == position.line && column == position.column) {
            return source.length();
        }
        return -1;
    }

    private static int positionToIndexExclusive(String source, Position position) {
        int index = positionToIndex(source, position);
        return index < 0 ? -1 : index + 1;
    }
}
