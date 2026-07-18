export type AppLanguage = 'JAVA' | 'FRONTEND' | 'GO' | 'PYTHON' | 'CPP' | string

export interface LanguageConfigField {
  key: string
  label: string
  type?: 'text' | 'number'
  placeholder?: string
  min?: number
  step?: number
  wide?: boolean
  options?: Array<{ value: string; label: string }>
}

export interface LanguageProfile {
  language: string
  label: string
  collectorType: 'RESIDENT' | 'BATCH'
  description: string
  fields: LanguageConfigField[]
}

export const languageOptions = [
  { value: 'JAVA', label: 'Java' },
  { value: 'FRONTEND', label: '前端 JS/TS' },
  { value: 'GO', label: 'Go' },
  { value: 'PYTHON', label: 'Python' },
  { value: 'CPP', label: 'C/C++' },
]

export const languageProfiles: Record<string, LanguageProfile> = {
  JAVA: {
    language: 'JAVA',
    label: 'Java',
    collectorType: 'RESIDENT',
    description: '用于识别 Java 源码目录、包路径、JaCoCo XML 覆盖率，并为 AI 验证提供源码证据。',
    fields: [
      { key: 'coverageFormat', label: '覆盖率格式', options: [{ value: 'jacoco-xml', label: 'JaCoCo XML' }] },
      { key: 'sourceRoot', label: '源码根目录', placeholder: 'src/main/java', wide: true },
      { key: 'packageRoot', label: '包根路径', placeholder: 'com.example' },
    ],
  },
  FRONTEND: {
    language: 'FRONTEND',
    label: '前端 JS/TS',
    collectorType: 'BATCH',
    description: '用于识别前端源码目录、SourceMap 位置和 Istanbul JSON 覆盖率，辅助定位源码证据。',
    fields: [
      { key: 'coverageFormat', label: '覆盖率格式', options: [{ value: 'istanbul-json', label: 'Istanbul JSON' }] },
      { key: 'sourceRoot', label: '源码根目录', placeholder: 'src', wide: true },
      { key: 'sourceMapRoot', label: 'SourceMap 根目录', placeholder: 'dist/assets', wide: true },
    ],
  },
  GO: batchProfile('GO', 'Go', [
    { key: 'moduleRoot', label: '模块根路径', placeholder: 'github.com/acme/service', wide: true },
    { key: 'profileFormat', label: 'Profile 格式', options: [{ value: 'go-cover', label: 'go cover' }, { value: 'lcov', label: 'LCOV' }] },
  ]),
  PYTHON: batchProfile('PYTHON', 'Python', [
    { key: 'packageRoot', label: '包根路径', placeholder: 'src', wide: true },
    { key: 'profileFormat', label: 'Profile 格式', options: [{ value: 'profile-json', label: 'Profile JSON' }, { value: 'lcov', label: 'LCOV' }] },
  ]),
  CPP: batchProfile('CPP', 'C/C++', [
    { key: 'sourceRoot', label: '源码根目录', placeholder: 'src', wide: true },
    { key: 'profileFormat', label: 'Profile 格式', options: [{ value: 'lcov', label: 'LCOV' }, { value: 'gcov', label: 'gcov' }] },
  ]),
}

export function resolveLanguageProfile(language?: AppLanguage) {
  return languageProfiles[String(language || 'JAVA').toUpperCase()] || languageProfiles.JAVA
}

export function isResidentLanguage(language?: AppLanguage) {
  return resolveLanguageProfile(language).collectorType === 'RESIDENT'
}

function batchProfile(language: string, label: string, fields: LanguageConfigField[]): LanguageProfile {
  return {
    language,
    label,
    collectorType: 'BATCH',
    description: `用于识别 ${label} 源码结构，支持配置的覆盖率报告格式，并为 AI 验证匹配源码证据。`,
    fields: [
      ...fields,
      { key: 'pathMapping', label: '源码路径映射', placeholder: '/workspace=/repo', wide: true },
    ],
  }
}
