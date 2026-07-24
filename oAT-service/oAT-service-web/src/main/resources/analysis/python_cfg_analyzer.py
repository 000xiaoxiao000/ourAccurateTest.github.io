import ast
import json
import sys


def text(node):
    try:
        return ast.unparse(node)
    except Exception:
        return ""


class Builder:
    def __init__(self, method):
        self.method = method
        self.nodes = []
        self.edges = []
        self.next_node = 0
        self.next_edge = 0
        self.partial = False

    def node(self, kind, label, expression="", line=None, depth=0, precise=True):
        order = self.next_node
        self.next_node += 1
        self.nodes.append({
            "key": str(order),
            "type": kind,
            "label": label,
            "expression": expression or "",
            "line": line,
            "order": order,
            "depth": depth,
            "precise": precise,
        })
        return str(order)

    def edge(self, source, target, kind="NEXT", label="continue", precise=True):
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

    def connect_incoming(self, incoming, target, kind="NEXT", label="continue", precise=True):
        for source in incoming:
            self.edge(source, target, kind, label, precise)

    def build(self, body):
        start = self.node("START", "start", self.method.get("name", ""), self.method.get("line"), 0, True)
        tail = self.block([start], body, 0)
        end = self.node("END", "continue", "", self.method.get("line"), 0, True)
        for source in tail["open"]:
            self.edge(source, end, "NEXT", "continue", True)
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
        if isinstance(statement, ast.If):
            return self.if_stmt(incoming, statement, depth)
        if isinstance(statement, (ast.For, ast.AsyncFor, ast.While)):
            return self.loop_stmt(incoming, statement, depth)
        if isinstance(statement, ast.Return):
            node = self.node("RETURN", "return", text(statement.value) if statement.value else "return", getattr(statement, "lineno", None), depth, True)
            self.connect_incoming(incoming, node, "RETURN", "return", True)
            return {"open": [], "terminal": [node]}
        if isinstance(statement, ast.Raise):
            node = self.node("THROW", "raise", text(statement.exc) if statement.exc else "raise", getattr(statement, "lineno", None), depth, True)
            self.connect_incoming(incoming, node, "THROW", "raise", True)
            return {"open": [], "terminal": [node]}
        if isinstance(statement, ast.Break):
            node = self.node("BREAK", "break", "break", getattr(statement, "lineno", None), depth, True)
            self.connect_incoming(incoming, node, "BREAK", "break", True)
            return {"open": [node], "terminal": []}
        if isinstance(statement, ast.Continue):
            node = self.node("CONTINUE", "continue", "continue", getattr(statement, "lineno", None), depth, True)
            self.connect_incoming(incoming, node, "CONTINUE", "continue", True)
            return {"open": [node], "terminal": []}
        if isinstance(statement, ast.Try):
            return self.try_stmt(incoming, statement, depth)
        precise = isinstance(statement, (ast.Assign, ast.AnnAssign, ast.AugAssign, ast.Expr, ast.Pass))
        if not precise:
            self.partial = True
        node = self.node("ACTION" if precise else "UNKNOWN_BLOCK", "execute" if precise else "not expanded", text(statement), getattr(statement, "lineno", None), depth, precise)
        self.connect_incoming(incoming, node, "NEXT", "continue", precise)
        return {"open": [node], "terminal": []}

    def if_stmt(self, incoming, statement, depth):
        decision = self.node("DECISION", "if", text(statement.test), getattr(statement, "lineno", None), depth, True)
        self.connect_incoming(incoming, decision, "NEXT", "continue", True)
        then_tail = self.block([decision], statement.body, depth + 1)
        self.relabel_last(decision, "TRUE", "yes")
        if statement.orelse:
            else_tail = self.block([decision], statement.orelse, depth + 1)
            self.relabel_first_not(decision, "FALSE", "no", "TRUE")
        else:
            passthrough = self.node("MERGE", "continue", "", getattr(statement, "lineno", None), depth + 1, True)
            self.edge(decision, passthrough, "FALSE", "no", True)
            else_tail = {"open": [passthrough], "terminal": []}
        return {
            "open": then_tail["open"] + else_tail["open"],
            "terminal": then_tail["terminal"] + else_tail["terminal"],
        }

    def try_stmt(self, incoming, statement, depth):
        try_node = self.node("TRY", "try", "", getattr(statement, "lineno", None), depth, True)
        self.connect_incoming(incoming, try_node, "NEXT", "enter try", True)
        try_tail = self.block([try_node], statement.body, depth + 1)
        open_nodes = list(try_tail["open"])
        terminal = list(try_tail["terminal"])
        for handler in statement.handlers:
            if handler.type is None:
                label = "Exception"
            else:
                label = text(handler.type)
            catch_node = self.node("CATCH", "except", label, getattr(handler, "lineno", None), depth + 1, True)
            self.edge(try_node, catch_node, "EXCEPTION", "except " + label, True)
            catch_tail = self.block([catch_node], handler.body, depth + 2)
            open_nodes.extend(catch_tail["open"])
            terminal.extend(catch_tail["terminal"])
        if statement.finalbody:
            finally_node = self.node("FINALLY", "finally", "", getattr(statement.finalbody[0], "lineno", getattr(statement, "lineno", None)), depth + 1, True)
            self.connect_incoming(open_nodes, finally_node, "FINALLY", "finally", True)
            finally_tail = self.block([finally_node], statement.finalbody, depth + 2)
            open_nodes = list(finally_tail["open"])
            terminal.extend(finally_tail["terminal"])
        return {"open": open_nodes, "terminal": terminal}

    def loop_stmt(self, incoming, statement, depth):
        condition = text(statement.test) if isinstance(statement, ast.While) else text(statement.target) + " in " + text(statement.iter)
        loop = self.node("LOOP", "loop", condition, getattr(statement, "lineno", None), depth, True)
        self.connect_incoming(incoming, loop, "NEXT", "continue", True)
        body_tail = self.block([loop], statement.body, depth + 1)
        self.relabel_last(loop, "LOOP_BODY", "yes")
        after = self.node("MERGE", "continue", "", getattr(statement, "lineno", None), depth, True)
        for source in body_tail["open"]:
            source_type = self.node_type(source)
            if source_type == "BREAK":
                self.edge(source, after, "BREAK", "break", True)
            else:
                self.edge(source, loop, "LOOP_BACK", "loop", True)
        self.edge(loop, after, "FALSE", "no", True)
        return {"open": [after], "terminal": body_tail["terminal"]}

    def relabel_last(self, source, kind, label):
        for index in range(len(self.edges) - 1, -1, -1):
            if self.edges[index]["source"] == source:
                self.edges[index]["type"] = kind
                self.edges[index]["label"] = label
                return

    def relabel_first_not(self, source, kind, label, skip):
        for edge in self.edges:
            if edge["source"] == source and edge["type"] != skip:
                edge["type"] = kind
                edge["label"] = label
                return

    def node_type(self, key):
        for node in self.nodes:
            if node["key"] == key:
                return node["type"]
        return "UNKNOWN_BLOCK"


def function_payload(node):
    params = [arg.arg for arg in getattr(node.args, "args", [])]
    method = {"name": node.name, "parameters": ",".join(params), "line": getattr(node, "lineno", None)}
    graph = Builder(method).build(node.body)
    method.update(graph)
    return method


def main():
    payload = json.loads(sys.stdin.read() or "{}")
    source = payload.get("source") or ""
    tree = ast.parse(source, filename=payload.get("path") or "<source>")
    methods = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            methods.append(function_payload(node))
    methods.sort(key=lambda item: item.get("line") or 0)
    print(json.dumps({"status": "OK", "methods": methods}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(json.dumps({"status": "ERROR", "message": str(exc)}, ensure_ascii=False))
        sys.exit(1)
