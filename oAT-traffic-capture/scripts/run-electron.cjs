const { spawn } = require('child_process')
const path = require('path')

const electronBin = require('electron')
const args = process.argv.slice(2)
const child = spawn(electronBin, args.length > 0 ? args : ['.'], {
  cwd: path.resolve(__dirname, '..'),
  env: process.env,
  stdio: ['inherit', 'pipe', 'pipe']
})

function writeFilteredStderr(chunk) {
  const text = chunk.toString()
  const lines = text.split(/(\r?\n)/)
  let pending = ''

  for (let index = 0; index < lines.length; index += 2) {
    const line = lines[index]
    const newline = lines[index + 1] || ''
    if (!line && !newline) continue
    if (line.includes('IMKCFRunLoopWakeUpReliable')) continue
    pending += line + newline
  }

  if (pending) {
    process.stderr.write(pending)
  }
}

child.stdout.on('data', (chunk) => process.stdout.write(chunk))
child.stderr.on('data', writeFilteredStderr)
child.on('error', (error) => {
  process.stderr.write(`${error.message}\n`)
  process.exit(1)
})
child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal)
    return
  }
  process.exit(code ?? 0)
})
