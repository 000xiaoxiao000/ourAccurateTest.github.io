import forge from 'node-forge'
import fs from 'fs'
import path from 'path'
import { app, shell } from 'electron'
import { execFile } from 'child_process'
import { promisify } from 'util'

const execFileAsync = promisify(execFile)
const CERT_COMMON_NAMES = ['oAT Traffic Capture Root CA', 'NodeMITMProxyCA']

export interface CertInfo {
  exists: boolean
  certPath?: string
  expiresAt?: string
}

function getCertDir(): string {
  return path.join(app.getPath('userData'), 'mitm-ca')
}

export function getProxyCaDir(): string {
  return getCertDir()
}

function getCertsDir(): string {
  return path.join(getCertDir(), 'certs')
}

function getKeysDir(): string {
  return path.join(getCertDir(), 'keys')
}

export function generateRootCert(): { certPath: string; keyPath: string } {
  const certDir = getCertDir()
  const certsDir = getCertsDir()
  const keysDir = getKeysDir()
  if (fs.existsSync(certDir)) fs.rmSync(certDir, { recursive: true, force: true })
  fs.mkdirSync(certsDir, { recursive: true })
  fs.mkdirSync(keysDir, { recursive: true })

  const keys = forge.pki.rsa.generateKeyPair(2048)
  const cert = forge.pki.createCertificate()
  cert.publicKey = keys.publicKey
  cert.serialNumber = Date.now().toString(16)
  cert.validity.notBefore = new Date()
  cert.validity.notAfter = new Date()
  cert.validity.notAfter.setFullYear(cert.validity.notBefore.getFullYear() + 10)

  const attrs = [
    { name: 'commonName', value: 'oAT Traffic Capture Root CA' },
    { name: 'organizationName', value: 'oAT Traffic Capture' }
  ]
  cert.setSubject(attrs)
  cert.setIssuer(attrs)
  cert.setExtensions([
    { name: 'basicConstraints', cA: true },
    { name: 'keyUsage', keyCertSign: true, digitalSignature: true, cRLSign: true }
  ])
  cert.sign(keys.privateKey, forge.md.sha256.create())

  const certPath = path.join(certsDir, 'ca.pem')
  const keyPath = path.join(keysDir, 'ca.private.key')
  fs.writeFileSync(certPath, forge.pki.certificateToPem(cert))
  fs.writeFileSync(keyPath, forge.pki.privateKeyToPem(keys.privateKey))
  fs.writeFileSync(path.join(keysDir, 'ca.public.key'), forge.pki.publicKeyToPem(keys.publicKey))

  return { certPath, keyPath }
}

export function getCertInfo(): CertInfo {
  const certPath = path.join(getCertsDir(), 'ca.pem')
  if (!fs.existsSync(certPath)) return { exists: false }

  const cert = forge.pki.certificateFromPem(fs.readFileSync(certPath, 'utf-8'))
  return {
    exists: true,
    certPath,
    expiresAt: cert.validity.notAfter.toLocaleDateString('zh-CN')
  }
}

export async function installCertMacOS(certPath: string): Promise<{ success: boolean; error?: string }> {
  const loginKeychain = path.join(app.getPath('home'), 'Library/Keychains/login.keychain-db')
  try {
    await deleteTrustedCerts(false)
    await execFileAsync('security', ['add-trusted-cert', '-r', 'trustRoot', '-k', loginKeychain, certPath])
    return { success: true }
  } catch (error: any) {
    try {
      await deleteTrustedCerts(true)
      const script = [
        `set certPath to POSIX path of ${JSON.stringify(certPath)}`,
        'do shell script "security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain " & quoted form of certPath with administrator privileges'
      ].join('\n')
      await execFileAsync('osascript', ['-e', script])
      return { success: true }
    } catch (adminError: any) {
      return {
        success: false,
        error: [
          '自动安装失败。',
          '请点击“打开目录”，手动将 ca.pem 导入“钥匙串访问”，并设置为“始终信任”。',
          adminError?.message || error?.message
        ].filter(Boolean).join('\n')
      }
    }
  }
}

function isDeleteNotFound(message: string): boolean {
  return /could not be found|unable to delete certificate matching|SecCertificateSearchCopyNext/i.test(message)
}

async function deleteTrustedCerts(includeSystem: boolean): Promise<string[]> {
  const loginKeychain = path.join(app.getPath('home'), 'Library/Keychains/login.keychain-db')
  const keychains = includeSystem ? [loginKeychain, '/Library/Keychains/System.keychain'] : [loginKeychain]
  const errors: string[] = []

  for (const commonName of CERT_COMMON_NAMES) {
    for (const keychain of keychains) {
      try {
        await execFileAsync('security', ['delete-certificate', '-c', commonName, keychain])
      } catch (error: any) {
        const message = error?.stderr || error?.message || String(error)
        if (!isDeleteNotFound(message)) {
          errors.push(message)
        }
      }
    }
  }

  return errors
}

export async function uninstallCertMacOS(): Promise<{ success: boolean; error?: string }> {
  const errors = await deleteTrustedCerts(true)

  if (errors.length === 0) {
    return { success: true }
  }

  try {
    const commonName = CERT_COMMON_NAMES[0]
    const script = [
      `set certName to ${JSON.stringify(commonName)}`,
      'do shell script "security delete-certificate -c " & quoted form of certName & " /Library/Keychains/System.keychain" with administrator privileges'
    ].join('\n')
    await execFileAsync('osascript', ['-e', script])
    return { success: true }
  } catch (error: any) {
    const message = error?.stderr || error?.message || String(error)
    if (isDeleteNotFound(message)) {
      return { success: true }
    }
    return {
      success: false,
      error: [
        '自动卸载失败。',
        '请打开“钥匙串访问”，搜索 oAT Traffic Capture Root CA，手动删除该证书。',
        error?.message || errors.join('\n')
      ].filter(Boolean).join('\n')
    }
  }
}

export function openCertFolder(certPath: string): void {
  shell.showItemInFolder(certPath)
}
