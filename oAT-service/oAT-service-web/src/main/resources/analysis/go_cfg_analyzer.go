package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/printer"
	"go/token"
	"io"
	"os"
)

type request struct {
	Path   string `json:"path"`
	Source string `json:"source"`
}

type response struct {
	Status  string   `json:"status"`
	Message string   `json:"message,omitempty"`
	Methods []method `json:"methods,omitempty"`
}

type method struct {
	Name         string   `json:"name"`
	Parameters   string   `json:"parameters"`
	Line         int      `json:"line"`
	ParseStatus  string   `json:"parseStatus"`
	Message      string   `json:"message"`
	Nodes        []node   `json:"nodes"`
	Edges        []edge   `json:"edges"`
	EntryNodeKey string   `json:"entryNodeKey"`
	ExitNodeKeys []string `json:"exitNodeKeys"`
}

type node struct {
	Key        string `json:"key"`
	Type       string `json:"type"`
	Label      string `json:"label"`
	Expression string `json:"expression"`
	Line       int    `json:"line,omitempty"`
	Order      int    `json:"order"`
	Depth      int    `json:"depth"`
	Precise    bool   `json:"precise"`
}

type edge struct {
	ID      string `json:"id"`
	Source  string `json:"source"`
	Target  string `json:"target"`
	Type    string `json:"type"`
	Label   string `json:"label"`
	Precise bool   `json:"precise"`
}

type tail struct {
	open     []string
	terminal []string
}

type builder struct {
	fset       *token.FileSet
	methodName string
	nodes      []node
	edges      []edge
	nextNode   int
	nextEdge   int
	partial    bool
}

func main() {
	input, _ := io.ReadAll(os.Stdin)
	var req request
	if err := json.Unmarshal(input, &req); err != nil {
		fail(err)
		return
	}
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, req.Path, req.Source, 0)
	if err != nil {
		fail(err)
		return
	}
	var methods []method
	for _, decl := range file.Decls {
		fn, ok := decl.(*ast.FuncDecl)
		if !ok || fn.Body == nil {
			continue
		}
		b := &builder{fset: fset, methodName: fn.Name.Name}
		methods = append(methods, b.build(fn))
	}
	write(response{Status: "OK", Methods: methods})
}

func fail(err error) {
	write(response{Status: "ERROR", Message: err.Error()})
	os.Exit(1)
}

func write(value any) {
	_ = json.NewEncoder(os.Stdout).Encode(value)
}

func (b *builder) build(fn *ast.FuncDecl) method {
	start := b.addNode("START", "start", fn.Name.Name, fn.Pos(), 0, true)
	bodyTail := b.block([]string{start}, fn.Body.List, 0)
	end := b.addNode("END", "continue", "", fn.End(), 0, true)
	for _, source := range bodyTail.open {
		b.addEdge(source, end, "NEXT", "continue", true)
	}
	for _, source := range bodyTail.terminal {
		b.addEdge(source, end, "NEXT", "end", true)
	}
	exits := []string{end}
	status := "PRECISE"
	msg := "执行路径已完整生成"
	if b.partial {
		status = "PARTIAL"
		msg = "部分语句暂未识别"
	}
	return method{
		Name:         fn.Name.Name,
		Parameters:   params(fn.Type.Params),
		Line:         b.line(fn.Pos()),
		ParseStatus:  status,
		Message:      msg,
		Nodes:        b.nodes,
		Edges:        b.edges,
		EntryNodeKey: start,
		ExitNodeKeys: exits,
	}
}

func (b *builder) block(incoming []string, statements []ast.Stmt, depth int) tail {
	open := incoming
	terminal := []string{}
	for _, stmt := range statements {
		if len(open) == 0 {
			extra := b.statement(nil, stmt, depth)
			terminal = append(terminal, extra.terminal...)
			continue
		}
		next := b.statement(open, stmt, depth)
		open = next.open
		terminal = append(terminal, next.terminal...)
	}
	return tail{open: open, terminal: terminal}
}

func (b *builder) statement(incoming []string, stmt ast.Stmt, depth int) tail {
	switch s := stmt.(type) {
	case *ast.BlockStmt:
		return b.block(incoming, s.List, depth)
	case *ast.IfStmt:
		return b.ifStmt(incoming, s, depth)
	case *ast.ForStmt:
		return b.loopStmt(incoming, s, expr(b.fset, s.Cond, "for"), s.Body, depth)
	case *ast.RangeStmt:
		return b.loopStmt(incoming, s, fmt.Sprintf("%s range %s", expr(b.fset, s.Key, ""), expr(b.fset, s.X, "")), s.Body, depth)
	case *ast.SwitchStmt:
		return b.switchStmt(incoming, s, expr(b.fset, s.Tag, "switch"), s.Body, depth)
	case *ast.TypeSwitchStmt:
		return b.switchStmt(incoming, s, expr(b.fset, s.Assign, "type switch"), s.Body, depth)
	case *ast.SelectStmt:
		return b.switchStmt(incoming, s, "select", s.Body, depth)
	case *ast.ReturnStmt:
		n := b.addNode("RETURN", "return", exprs(b.fset, s.Results), s.Pos(), depth, true)
		b.connect(incoming, n, "RETURN", "return", true)
		return tail{terminal: []string{n}}
	case *ast.BranchStmt:
		if s.Tok == token.BREAK {
			n := b.addNode("BREAK", "break", "break", s.Pos(), depth, true)
			b.connect(incoming, n, "BREAK", "break", true)
			return tail{open: []string{n}}
		}
		if s.Tok == token.CONTINUE {
			n := b.addNode("CONTINUE", "continue", "continue", s.Pos(), depth, true)
			b.connect(incoming, n, "CONTINUE", "continue", true)
			return tail{open: []string{n}}
		}
	}
	precise := true
	switch stmt.(type) {
	case *ast.AssignStmt, *ast.ExprStmt, *ast.DeclStmt, *ast.IncDecStmt, *ast.SendStmt, *ast.DeferStmt, *ast.GoStmt, *ast.EmptyStmt:
	default:
		precise = false
		b.partial = true
	}
	kind := "ACTION"
	label := "execute"
	if !precise {
		kind = "UNKNOWN_BLOCK"
		label = "not expanded"
	}
	n := b.addNode(kind, label, expr(b.fset, stmt, ""), stmt.Pos(), depth, precise)
	b.connect(incoming, n, "NEXT", "continue", precise)
	return tail{open: []string{n}}
}

func (b *builder) ifStmt(incoming []string, stmt *ast.IfStmt, depth int) tail {
	decision := b.addNode("DECISION", "if", expr(b.fset, stmt.Cond, ""), stmt.Pos(), depth, true)
	b.connect(incoming, decision, "NEXT", "continue", true)
	thenTail := b.block([]string{decision}, stmt.Body.List, depth+1)
	b.relabelLast(decision, "TRUE", "yes")
	var elseTail tail
	if stmt.Else != nil {
		elseTail = b.statement([]string{decision}, stmt.Else, depth+1)
		b.relabelFirstNot(decision, "FALSE", "no", "TRUE")
	} else {
		pass := b.addNode("MERGE", "continue", "", stmt.Pos(), depth+1, true)
		b.addEdge(decision, pass, "FALSE", "no", true)
		elseTail = tail{open: []string{pass}}
	}
	return tail{open: append(thenTail.open, elseTail.open...), terminal: append(thenTail.terminal, elseTail.terminal...)}
}

func (b *builder) loopStmt(incoming []string, stmt ast.Stmt, condition string, body *ast.BlockStmt, depth int) tail {
	loop := b.addNode("LOOP", "loop", condition, stmt.Pos(), depth, true)
	b.connect(incoming, loop, "NEXT", "continue", true)
	bodyTail := b.block([]string{loop}, body.List, depth+1)
	b.relabelLast(loop, "LOOP_BODY", "yes")
	after := b.addNode("MERGE", "continue", "", stmt.Pos(), depth, true)
	for _, source := range bodyTail.open {
		if b.nodeType(source) == "BREAK" {
			b.addEdge(source, after, "BREAK", "break", true)
		} else {
			b.addEdge(source, loop, "LOOP_BACK", "loop", true)
		}
	}
	b.addEdge(loop, after, "FALSE", "no", true)
	return tail{open: []string{after}, terminal: bodyTail.terminal}
}

func (b *builder) switchStmt(incoming []string, stmt ast.Stmt, selector string, body *ast.BlockStmt, depth int) tail {
	decision := b.addNode("SWITCH", "switch", selector, stmt.Pos(), depth, true)
	b.connect(incoming, decision, "NEXT", "continue", true)
	open := []string{}
	terminal := []string{}
	hasDefault := false
	for _, item := range body.List {
		var label string
		var statements []ast.Stmt
		isDefault := false
		switch clause := item.(type) {
		case *ast.CaseClause:
			statements = clause.Body
			if len(clause.List) == 0 {
				label = "default"
				isDefault = true
			} else {
				label = exprs(b.fset, clause.List)
			}
		case *ast.CommClause:
			statements = clause.Body
			if clause.Comm == nil {
				label = "default"
				isDefault = true
			} else {
				label = expr(b.fset, clause.Comm, "case")
			}
		default:
			continue
		}
		if isDefault {
			hasDefault = true
		}
		caseNode := b.addNode("CASE", label, "", item.Pos(), depth+1, true)
		if isDefault {
			b.addEdge(decision, caseNode, "DEFAULT", label, true)
		} else {
			b.addEdge(decision, caseNode, "CASE", label, true)
		}
		caseTail := b.block([]string{caseNode}, statements, depth+2)
		open = append(open, caseTail.open...)
		terminal = append(terminal, caseTail.terminal...)
	}
	if !hasDefault {
		pass := b.addNode("MERGE", "continue", "", stmt.Pos(), depth+1, true)
		b.addEdge(decision, pass, "DEFAULT", "default", true)
		open = append(open, pass)
	}
	return tail{open: open, terminal: terminal}
}

func (b *builder) addNode(kind, label, expression string, pos token.Pos, depth int, precise bool) string {
	key := fmt.Sprintf("%d", b.nextNode)
	b.nodes = append(b.nodes, node{Key: key, Type: kind, Label: label, Expression: expression, Line: b.line(pos), Order: b.nextNode, Depth: depth, Precise: precise})
	b.nextNode++
	return key
}

func (b *builder) addEdge(source, target, kind, label string, precise bool) {
	if source == "" || target == "" {
		return
	}
	b.edges = append(b.edges, edge{ID: fmt.Sprintf("e%d", b.nextEdge), Source: source, Target: target, Type: kind, Label: label, Precise: precise})
	b.nextEdge++
}

func (b *builder) connect(incoming []string, target, kind, label string, precise bool) {
	for _, source := range incoming {
		b.addEdge(source, target, kind, label, precise)
	}
}

func (b *builder) relabelLast(source, kind, label string) {
	for i := len(b.edges) - 1; i >= 0; i-- {
		if b.edges[i].Source == source {
			b.edges[i].Type = kind
			b.edges[i].Label = label
			return
		}
	}
}

func (b *builder) relabelFirstNot(source, kind, label, skip string) {
	for i := range b.edges {
		if b.edges[i].Source == source && b.edges[i].Type != skip {
			b.edges[i].Type = kind
			b.edges[i].Label = label
			return
		}
	}
}

func (b *builder) nodeType(key string) string {
	for _, node := range b.nodes {
		if node.Key == key {
			return node.Type
		}
	}
	return "UNKNOWN_BLOCK"
}

func (b *builder) line(pos token.Pos) int {
	if !pos.IsValid() {
		return 0
	}
	return b.fset.Position(pos).Line
}

func expr(fset *token.FileSet, n any, fallback string) string {
	if n == nil {
		return fallback
	}
	var buf bytes.Buffer
	if err := printer.Fprint(&buf, fset, n); err != nil {
		return fallback
	}
	return buf.String()
}

func exprs(fset *token.FileSet, nodes []ast.Expr) string {
	values := make([]string, 0, len(nodes))
	for _, n := range nodes {
		values = append(values, expr(fset, n, ""))
	}
	var buf bytes.Buffer
	for i, value := range values {
		if i > 0 {
			buf.WriteString(", ")
		}
		buf.WriteString(value)
	}
	return buf.String()
}

func params(fields *ast.FieldList) string {
	if fields == nil {
		return ""
	}
	values := []string{}
	for _, field := range fields.List {
		t := expr(token.NewFileSet(), field.Type, "")
		if len(field.Names) == 0 {
			values = append(values, t)
			continue
		}
		for _, name := range field.Names {
			values = append(values, name.Name+" "+t)
		}
	}
	var buf bytes.Buffer
	for i, value := range values {
		if i > 0 {
			buf.WriteString(",")
		}
		buf.WriteString(value)
	}
	return buf.String()
}
