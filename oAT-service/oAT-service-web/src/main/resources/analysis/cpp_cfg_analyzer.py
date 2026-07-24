import json
import os
import subprocess
import sys
import tempfile


def node_text(source, node):
    rng = node.get("range") or {}
    begin = (rng.get("begin") or {}).get("offset")
    end = (rng.get("end") or {}).get("offset")
    tok_len = (rng.get("end") or {}).get("tokLen") or 1
    if begin is None or end is None:
        return ""
    return source[begin:end + tok_len].strip()


def child_statements(node):
    return [item for item in node.get("inner") or [] if item.get("kind", "").endswith("Stmt")]


class Builder:
    def __init__(self, source, method):
        self.source = source
        self.method = method
        self.nodes = []
        self.edges = []
        self.next_node = 0
        self.next_edge = 0
        self.partial = False

    def line(self, node):
        return ((node.get("loc") or {}).get("line")
                or ((node.get("range") or {}).get("begin") or {}).get("line")
                or 0)

    def add_node(self, kind, label, expression, source_node, depth, precise=True):
        key = str(self.next_node)
        self.nodes.append({
            "key": key,
            "type": kind,
            "label": label,
            "expression": expression or "",
            "line": self.line(source_node),
            "order": self.next_node,
            "depth": depth,
            "precise": precise,
        })
        self.next_node += 1
        return key

    def add_edge(self, source, target, kind="NEXT", label="continue", precise=True):
        if source is None or target is None:
            return
        self.edges.append({
            "id": "e{}".format(self.next_edge),
            "source": source,
            "target": target,
            "type": kind,
            "label": label,
            "precise": precise,
        })
        self.next_edge += 1

    def connect(self, incoming, target, kind="NEXT", label="continue", precise=True):
        for source in incoming:
            self.add_edge(source, target, kind, label, precise)

    def build(self, body):
        start = self.add_node("START", "start", self.method.get("name", ""), self.method, 0, True)
        tail = self.block([start], child_statements(body), 0)
        end = self.add_node("END", "continue", "", body, 0, True)
        for source in tail["open"]:
            self.add_edge(source, end, "NEXT", "continue", True)
        exits = tail["terminal"] or [end]
        return {
            "parseStatus": "PARTIAL" if self.partial else "PRECISE",
            "message": "执行路径已完整生成" if not self.partial else "部分语句暂未识别",
            "nodes": self.nodes,
            "edges": self.edges,
            "entryNodeKey": start,
            "exitNodeKeys": exits,
        }

    def block(self, incoming, statements, depth):
        open_nodes = incoming
        terminal = []
        for statement in statements:
            if not open_nodes:
                extra = self.statement([], statement, depth)
                terminal.extend(extra["terminal"])
                continue
            result = self.statement(open_nodes, statement, depth)
            open_nodes = result["open"]
            terminal.extend(result["terminal"])
        return {"open": open_nodes, "terminal": terminal}

    def statement(self, incoming, statement, depth):
        kind = statement.get("kind")
        if kind == "CompoundStmt":
            return self.block(incoming, child_statements(statement), depth)
        if kind == "IfStmt":
            return self.if_stmt(incoming, statement, depth)
        if kind in ("ForStmt", "WhileStmt", "DoStmt"):
            return self.loop_stmt(incoming, statement, depth)
        if kind == "CXXTryStmt":
            return self.try_stmt(incoming, statement, depth)
        if kind == "ReturnStmt":
            expression = ""
            inner = statement.get("inner") or []
            if inner:
                expression = node_text(self.source, inner[-1])
            node = self.add_node("RETURN", "return", expression or "return", statement, depth, True)
            self.connect(incoming, node, "RETURN", "return", True)
            return {"open": [], "terminal": [node]}
        if kind == "CXXThrowExpr":
            expression = node_text(self.source, statement) or "throw"
            node = self.add_node("THROW", "throw", expression, statement, depth, True)
            self.connect(incoming, node, "THROW", "throw", True)
            return {"open": [], "terminal": [node]}
        if kind == "BreakStmt":
            node = self.add_node("BREAK", "break", "break", statement, depth, True)
            self.connect(incoming, node, "BREAK", "break", True)
            return {"open": [node], "terminal": []}
        if kind == "ContinueStmt":
            node = self.add_node("CONTINUE", "continue", "continue", statement, depth, True)
            self.connect(incoming, node, "CONTINUE", "continue", True)
            return {"open": [node], "terminal": []}
        precise = kind in ("DeclStmt", "BinaryOperator", "CallExpr", "CXXOperatorCallExpr", "UnaryOperator")
        if not precise:
            self.partial = True
        node = self.add_node("ACTION" if precise else "UNKNOWN_BLOCK",
                             "execute" if precise else "not expanded",
                             node_text(self.source, statement), statement, depth, precise)
        self.connect(incoming, node, "NEXT", "continue", precise)
        return {"open": [node], "terminal": []}

    def if_stmt(self, incoming, statement, depth):
        inner = statement.get("inner") or []
        condition = inner[0] if inner else statement
        decision = self.add_node("DECISION", "if", node_text(self.source, condition), statement, depth, True)
        self.connect(incoming, decision, "NEXT", "continue", True)
        then_stmt = inner[1] if len(inner) > 1 else {"kind": "CompoundStmt", "inner": []}
        then_tail = self.statement([decision], then_stmt, depth + 1)
        self.relabel_last(decision, "TRUE", "yes")
        if len(inner) > 2:
            else_tail = self.statement([decision], inner[2], depth + 1)
            self.relabel_first_not(decision, "FALSE", "no", "TRUE")
        else:
            passthrough = self.add_node("MERGE", "continue", "", statement, depth + 1, True)
            self.add_edge(decision, passthrough, "FALSE", "no", True)
            else_tail = {"open": [passthrough], "terminal": []}
        return {
            "open": then_tail["open"] + else_tail["open"],
            "terminal": then_tail["terminal"] + else_tail["terminal"],
        }

    def try_stmt(self, incoming, statement, depth):
        inner = statement.get("inner") or []
        try_body = next((item for item in inner if item.get("kind") == "CompoundStmt"), {"kind": "CompoundStmt", "inner": []})
        catches = [item for item in inner if item.get("kind") == "CXXCatchStmt"]
        try_node = self.add_node("TRY", "try", "", statement, depth, True)
        self.connect(incoming, try_node, "NEXT", "enter try", True)
        try_tail = self.statement([try_node], try_body, depth + 1)
        open_nodes = list(try_tail["open"])
        terminal = list(try_tail["terminal"])
        for catch_stmt in catches:
            label = node_text(self.source, catch_stmt) or "catch"
            catch_node = self.add_node("CATCH", "catch", label, catch_stmt, depth + 1, True)
            self.add_edge(try_node, catch_node, "EXCEPTION", "catch", True)
            catch_body = next((item for item in catch_stmt.get("inner") or [] if item.get("kind") == "CompoundStmt"), {"kind": "CompoundStmt", "inner": []})
            catch_tail = self.statement([catch_node], catch_body, depth + 2)
            open_nodes.extend(catch_tail["open"])
            terminal.extend(catch_tail["terminal"])
        return {"open": open_nodes, "terminal": terminal}

    def loop_stmt(self, incoming, statement, depth):
        inner = statement.get("inner") or []
        body = next((item for item in reversed(inner) if item.get("kind") == "CompoundStmt"), {"kind": "CompoundStmt", "inner": []})
        condition = node_text(self.source, statement)
        decision = self.add_node("LOOP", "loop", condition, statement, depth, True)
        self.connect(incoming, decision, "NEXT", "continue", True)
        body_tail = self.statement([decision], body, depth + 1)
        self.relabel_last(decision, "LOOP_BODY", "yes")
        after = self.add_node("MERGE", "continue", "", statement, depth, True)
        for source in body_tail["open"]:
            if self.node_type(source) == "BREAK":
                self.add_edge(source, after, "BREAK", "break", True)
            else:
                self.add_edge(source, decision, "LOOP_BACK", "loop", True)
        self.add_edge(decision, after, "FALSE", "no", True)
        return {"open": [after], "terminal": body_tail["terminal"]}

    def relabel_last(self, source, kind, label):
        for edge in reversed(self.edges):
            if edge["source"] == source:
                edge["type"] = kind
                edge["label"] = label
                return

    def relabel_first_not(self, source, kind, label, skip):
        for edge in self.edges:
            if edge["source"] == source and edge["type"] != skip:
                edge["type"] = kind
                edge["label"] = label
                return

    def node_type(self, key):
        for item in self.nodes:
            if item["key"] == key:
                return item["type"]
        return "UNKNOWN_BLOCK"


def find_functions(node, source_file, result):
    if isinstance(node, dict):
        kind = node.get("kind")
        loc = node.get("loc") or {}
        if kind in ("FunctionDecl", "CXXMethodDecl") and node.get("name") and loc.get("file") == source_file:
            body = next((item for item in node.get("inner") or [] if item.get("kind") == "CompoundStmt"), None)
            if body:
                result.append((node, body))
        for child in node.get("inner") or []:
            find_functions(child, source_file, result)


def params(function_node):
    values = []
    for child in function_node.get("inner") or []:
        if child.get("kind") == "ParmVarDecl":
            name = child.get("name") or ""
            typ = ((child.get("type") or {}).get("qualType") or "").strip()
            values.append((name + " " + typ).strip())
    return ",".join(values)


def main():
    payload = json.loads(sys.stdin.read() or "{}")
    source = payload.get("source") or ""
    path = payload.get("path") or "source.cpp"
    suffix = ".cpp" if path.lower().endswith((".cpp", ".cc", ".cxx", ".hpp")) else ".c"
    compiler = os.environ.get("OAT_CLANGXX_EXECUTABLE" if suffix == ".cpp" else "OAT_CLANG_EXECUTABLE") or ("clang++" if suffix == ".cpp" else "clang")
    with tempfile.NamedTemporaryFile("w", suffix=suffix, delete=False) as tmp:
        tmp.write(source)
        tmp_path = tmp.name
    try:
        proc = subprocess.run(
            [compiler, "-fsyntax-only", "-Xclang", "-ast-dump=json", tmp_path],
            text=True,
            capture_output=True,
            timeout=8,
        )
        if proc.returncode != 0:
            raise RuntimeError(proc.stderr.strip() or "clang failed")
        root = json.loads(proc.stdout)
        functions = []
        find_functions(root, tmp_path, functions)
        methods = []
        for function, body in functions:
            graph = Builder(source, function).build(body)
            methods.append({
                "name": function.get("name") or "function",
                "parameters": params(function),
                "line": ((function.get("loc") or {}).get("line") or 1),
                **graph,
            })
        print(json.dumps({"status": "OK", "methods": methods}, ensure_ascii=False))
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(json.dumps({"status": "ERROR", "message": str(exc)}, ensure_ascii=False))
        sys.exit(1)
