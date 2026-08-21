-- A1 需求全系统级：oat_usecase 增加 related_app_ids 列（跨系统业务需求可见范围）。
-- 幂等：重复执行无副作用。应用重启时由 flyway 自动重放。
ALTER TABLE oat_usecase ADD COLUMN IF NOT EXISTS related_app_ids jsonb;
