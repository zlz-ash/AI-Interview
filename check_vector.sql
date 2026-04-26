\pset format unaligned
\pset fieldsep '|'
\echo === count_total ===
SELECT COUNT(*) FROM vector_store WHERE metadata::jsonb->>'kb_id' = '5';
\echo === id_format_sample ===
SELECT id::text AS vs_id, LENGTH(content) AS clen, metadata::jsonb->>'kb_id' AS kb, (metadata::jsonb ? 'id') AS meta_has_id FROM vector_store WHERE metadata::jsonb->>'kb_id' = '5' LIMIT 5;
\echo === metadata_keys ===
SELECT DISTINCT jsonb_object_keys(metadata::jsonb) FROM vector_store WHERE metadata::jsonb->>'kb_id' = '5';
