const fs = require('fs')

function readStdin() {
  return fs.readFileSync(0, 'utf8')
}

function nodeText(source, node) {
  if (!node || typeof node.start !== 'number' || typeof node.end !== 'number') return ''
  return source.slice(node.start, node.end).trim()
}

class Builder {
  constructor(source, method) {
    this.source = source
    this.method = method
    this.nodes = []
    this.edges = []
    this.nextNode = 0
    this.nextEdge = 0
    this.partial = false
  }

  addNode(type, label, expression, sourceNode, depth, precise = true) {
    const key = String(this.nextNode)
    this.nodes.push({
      key,
      type,
      label,
      expression: expression || '',
      line: sourceNode?.loc?.start?.line || this.method.line || 1,
      order: this.nextNode,
      depth,
      precise,
    })
    this.nextNode += 1
    return key
  }

  addEdge(source, target, type = 'NEXT', label = 'continue', precise = true) {
    if (!source || !target) return
    this.edges.push({ id: `e${this.nextEdge++}`, source, target, type, label, precise })
  }

  connect(incoming, target, type = 'NEXT', label = 'continue', precise = true) {
    for (const source of incoming) this.addEdge(source, target, type, label, precise)
  }

  build(body) {
    const start = this.addNode('START', 'start', this.method.name, body, 0, true)
    const tail = this.block([start], body?.body || [], 0)
    const end = this.addNode('END', 'continue', '', body, 0, true)
    for (const source of tail.open) this.addEdge(source, end, 'NEXT', 'continue', true)
    const exits = tail.terminal.length ? tail.terminal : [end]
    return {
      parseStatus: this.partial ? 'PARTIAL' : 'PRECISE',
      message: this.partial ? '部分语句暂未识别' : '执行路径已完整生成',
      nodes: this.nodes,
      edges: this.edges,
      entryNodeKey: start,
      exitNodeKeys: exits,
    }
  }

  block(incoming, statements, depth) {
    let open = incoming
    const terminal = []
    for (const statement of statements || []) {
      if (!open.length) {
        const extra = this.statement([], statement, depth)
        terminal.push(...extra.terminal)
        continue
      }
      const next = this.statement(open, statement, depth)
      open = next.open
      terminal.push(...next.terminal)
    }
    return { open, terminal }
  }

  statement(incoming, statement, depth) {
    if (!statement) return { open: incoming, terminal: [] }
    if (statement.type === 'BlockStatement') return this.block(incoming, statement.body || [], depth)
    if (statement.type === 'IfStatement') return this.ifStatement(incoming, statement, depth)
    if (['ForStatement', 'ForInStatement', 'ForOfStatement', 'WhileStatement', 'DoWhileStatement'].includes(statement.type)) {
      return this.loopStatement(incoming, statement, depth)
    }
    if (statement.type === 'SwitchStatement') return this.switchStatement(incoming, statement, depth)
    if (statement.type === 'TryStatement') return this.tryStatement(incoming, statement, depth)
    if (statement.type === 'ReturnStatement') {
      const node = this.addNode('RETURN', 'return', nodeText(this.source, statement.argument) || 'return', statement, depth, true)
      this.connect(incoming, node, 'RETURN', 'return', true)
      return { open: [], terminal: [node] }
    }
    if (statement.type === 'ThrowStatement') {
      const node = this.addNode('THROW', 'throw', nodeText(this.source, statement.argument) || 'throw', statement, depth, true)
      this.connect(incoming, node, 'THROW', 'throw', true)
      return { open: [], terminal: [node] }
    }
    if (statement.type === 'BreakStatement') {
      const node = this.addNode('BREAK', 'break', 'break', statement, depth, true)
      this.connect(incoming, node, 'BREAK', 'break', true)
      return { open: [node], terminal: [] }
    }
    if (statement.type === 'ContinueStatement') {
      const node = this.addNode('CONTINUE', 'continue', 'continue', statement, depth, true)
      this.connect(incoming, node, 'CONTINUE', 'continue', true)
      return { open: [node], terminal: [] }
    }
    const precise = ['ExpressionStatement', 'VariableDeclaration', 'EmptyStatement'].includes(statement.type)
    if (!precise) this.partial = true
    const node = this.addNode(precise ? 'ACTION' : 'UNKNOWN_BLOCK', precise ? 'execute' : 'not expanded', nodeText(this.source, statement), statement, depth, precise)
    this.connect(incoming, node, 'NEXT', 'continue', precise)
    return { open: [node], terminal: [] }
  }

  ifStatement(incoming, statement, depth) {
    const decision = this.addNode('DECISION', 'if', nodeText(this.source, statement.test), statement, depth, true)
    this.connect(incoming, decision, 'NEXT', 'continue', true)
    const thenTail = this.statement([decision], statement.consequent, depth + 1)
    this.relabelLast(decision, 'TRUE', 'yes')
    let elseTail
    if (statement.alternate) {
      elseTail = this.statement([decision], statement.alternate, depth + 1)
      this.relabelFirstNot(decision, 'FALSE', 'no', 'TRUE')
    } else {
      const pass = this.addNode('MERGE', 'continue', '', statement, depth + 1, true)
      this.addEdge(decision, pass, 'FALSE', 'no', true)
      elseTail = { open: [pass], terminal: [] }
    }
    return { open: [...thenTail.open, ...elseTail.open], terminal: [...thenTail.terminal, ...elseTail.terminal] }
  }

  loopStatement(incoming, statement, depth) {
    const condition = nodeText(this.source, statement.test || statement.right || statement)
    const loop = this.addNode('LOOP', 'loop', condition || statement.type, statement, depth, true)
    this.connect(incoming, loop, 'NEXT', 'continue', true)
    const bodyTail = this.statement([loop], statement.body, depth + 1)
    this.relabelLast(loop, 'LOOP_BODY', 'yes')
    const after = this.addNode('MERGE', 'continue', '', statement, depth, true)
    for (const source of bodyTail.open) {
      if (this.nodeType(source) === 'BREAK') this.addEdge(source, after, 'BREAK', 'break', true)
      else this.addEdge(source, loop, 'LOOP_BACK', 'loop', true)
    }
    this.addEdge(loop, after, 'FALSE', 'no', true)
    return { open: [after], terminal: bodyTail.terminal }
  }

  switchStatement(incoming, statement, depth) {
    const decision = this.addNode('SWITCH', 'switch', nodeText(this.source, statement.discriminant), statement, depth, true)
    this.connect(incoming, decision, 'NEXT', 'continue', true)
    const open = []
    const terminal = []
    for (const item of statement.cases || []) {
      const label = item.test ? nodeText(this.source, item.test) : 'default'
      const caseNode = this.addNode('CASE', label, '', item, depth + 1, true)
      this.addEdge(decision, caseNode, item.test ? 'CASE' : 'DEFAULT', label, true)
      const caseTail = this.block([caseNode], item.consequent || [], depth + 2)
      open.push(...caseTail.open)
      terminal.push(...caseTail.terminal)
    }
    if (!(statement.cases || []).some(item => !item.test)) {
      const pass = this.addNode('MERGE', 'continue', '', statement, depth + 1, true)
      this.addEdge(decision, pass, 'DEFAULT', 'default', true)
      open.push(pass)
    }
    return { open, terminal }
  }

  tryStatement(incoming, statement, depth) {
    const tryNode = this.addNode('TRY', 'try', '', statement, depth, true)
    this.connect(incoming, tryNode, 'NEXT', 'enter try', true)
    const tryTail = this.block([tryNode], statement.block?.body || [], depth + 1)
    let open = [...tryTail.open]
    const terminal = [...tryTail.terminal]
    if (statement.handler) {
      const param = nodeText(this.source, statement.handler.param) || 'error'
      const catchNode = this.addNode('CATCH', 'catch', param, statement.handler, depth + 1, true)
      this.addEdge(tryNode, catchNode, 'EXCEPTION', `catch ${param}`, true)
      const catchTail = this.block([catchNode], statement.handler.body?.body || [], depth + 2)
      open.push(...catchTail.open)
      terminal.push(...catchTail.terminal)
    }
    if (statement.finalizer) {
      const finallyNode = this.addNode('FINALLY', 'finally', '', statement.finalizer, depth + 1, true)
      this.connect(open, finallyNode, 'FINALLY', 'finally', true)
      const finallyTail = this.block([finallyNode], statement.finalizer.body || [], depth + 2)
      open = [...finallyTail.open]
      terminal.push(...finallyTail.terminal)
    }
    return { open, terminal }
  }

  relabelLast(source, type, label) {
    for (let i = this.edges.length - 1; i >= 0; i--) {
      if (this.edges[i].source === source) {
        this.edges[i].type = type
        this.edges[i].label = label
        return
      }
    }
  }

  relabelFirstNot(source, type, label, skip) {
    for (const edge of this.edges) {
      if (edge.source === source && edge.type !== skip) {
        edge.type = type
        edge.label = label
        return
      }
    }
  }

  nodeType(key) {
    return this.nodes.find(item => item.key === key)?.type || 'UNKNOWN_BLOCK'
  }
}

function functionName(node) {
  if (node.type === 'FunctionDeclaration') return node.id?.name
  if (node.type === 'VariableDeclarator') return node.id?.name
  if (node.type === 'ClassMethod' || node.type === 'ObjectMethod') return node.key?.name || node.key?.value
  return ''
}

function functionBody(node) {
  if (node.type === 'VariableDeclarator') return node.init?.body
  return node.body
}

function functionParams(node) {
  const params = node.type === 'VariableDeclarator' ? node.init?.params : node.params
  return (params || []).map(param => nodeText(globalSource, param)).join(',')
}

function collect(node, result) {
  if (!node || typeof node !== 'object') return
  const isFunction = node.type === 'FunctionDeclaration'
    || node.type === 'ClassMethod'
    || node.type === 'ObjectMethod'
    || (node.type === 'VariableDeclarator' && ['ArrowFunctionExpression', 'FunctionExpression'].includes(node.init?.type))
  if (isFunction && functionName(node) && functionBody(node)?.type === 'BlockStatement') {
    result.push(node)
  }
  for (const value of Object.values(node)) {
    if (Array.isArray(value)) value.forEach(item => collect(item, result))
    else if (value && typeof value === 'object' && value.type) collect(value, result)
  }
}

let globalSource = ''

function main() {
  const payload = JSON.parse(readStdin() || '{}')
  globalSource = payload.source || ''
  const parser = require('@babel/parser')
  const ast = parser.parse(globalSource, {
    sourceType: 'unambiguous',
    errorRecovery: false,
    plugins: ['typescript', 'jsx', 'classProperties', 'decorators-legacy'],
  })
  const functions = []
  collect(ast.program, functions)
  functions.sort((a, b) => (a.loc?.start?.line || 0) - (b.loc?.start?.line || 0))
  const methods = functions.map(fn => {
    const name = functionName(fn)
    const method = { name, parameters: functionParams(fn), line: fn.loc?.start?.line || 1 }
    return { ...method, ...new Builder(globalSource, method).build(functionBody(fn)) }
  })
  process.stdout.write(JSON.stringify({ status: 'OK', methods }))
}

try {
  main()
} catch (error) {
  process.stdout.write(JSON.stringify({ status: 'ERROR', message: error.message || String(error) }))
  process.exit(1)
}
