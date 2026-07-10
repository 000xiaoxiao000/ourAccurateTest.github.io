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
    description: 'Java 采集源用于记录应用源码与版本配置。',
    fields: [
      { key: 'sourceRoot', label: '源码根目录', placeholder: 'src/main/java', wide: true },
      { key: 'packageRoot', label: '包根路径', placeholder: 'com.example' },
    ],
  },
  FRONTEND: {
    language: 'FRONTEND',
    label: '前端 JS/TS',
    collectorType: 'BATCH',
    description: '前端采集源按批量上报判活，可配置源码映射和静默阈值。',
    fields: [
      { key: 'sourceRoot', label: '源码根目录', placeholder: 'src', wide: true },
      { key: 'sourceMapRoot', label: 'SourceMap 根目录', placeholder: 'dist/assets', wide: true },
      { key: 'silentThresholdSeconds', label: '静默阈值（秒）', type: 'number', min: 30 },
      { key: 'reportIntervalSeconds', label: '期望上报周期（秒）', type: 'number', min: 5 },
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
    description: `${label} 采集源按批量文件或 SDK 上报判活，静默阈值用于采集源健康度。`,
    fields: [
      ...fields,
      { key: 'pathMapping', label: '源码路径映射', placeholder: '/workspace=/repo', wide: true },
      { key: 'silentThresholdSeconds', label: '静默阈值（秒）', type: 'number', min: 30 },
      { key: 'reportIntervalSeconds', label: '期望上报周期（秒）', type: 'number', min: 5 },
    ],
  }
}
