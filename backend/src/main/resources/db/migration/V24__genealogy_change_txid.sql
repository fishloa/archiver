ALTER TABLE gen_change ADD COLUMN txid bigint;
ALTER TABLE gen_change ALTER COLUMN txid SET DEFAULT txid_current();

CREATE INDEX idx_gen_change_txid ON gen_change (txid);
