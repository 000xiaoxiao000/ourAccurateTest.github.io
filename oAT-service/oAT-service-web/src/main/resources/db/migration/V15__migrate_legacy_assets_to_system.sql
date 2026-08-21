-- V15: 将遗留资产归属到系统，并为需求表增加 app_id 列（支持系统级需求）
-- 背景：历史资产的系统归属只存在于 metadata_json.appId（软过滤），无独立索引列；
--       本迁移将其落到 app_id 列，并把无归属资产归到项目的唯一系统（range_type='only'）。
-- 该脚本幂等，可安全在已手动执行过的库上重放。

CREATE TABLE IF NOT EXISTS oat_verification_asset_bak_20260821 AS SELECT * FROM oat_verification_asset;
CREATE TABLE IF NOT EXISTS oat_usecase_bak_20260821 AS SELECT * FROM oat_usecase;

ALTER TABLE oat_verification_asset ADD COLUMN IF NOT EXISTS app_id varchar(255);
ALTER TABLE oat_usecase ADD COLUMN IF NOT EXISTS app_id varchar(255);

UPDATE oat_verification_asset SET app_id = metadata_json->>'appId'
WHERE app_id IS NULL AND metadata_json->>'appId' IS NOT NULL AND metadata_json->>'appId' <> '';

UPDATE oat_verification_asset SET app_id = (
  SELECT a.id FROM oat_app a
  WHERE a.project_id = oat_verification_asset.project_id AND a.range_type='only' LIMIT 1)
WHERE app_id IS NULL;
