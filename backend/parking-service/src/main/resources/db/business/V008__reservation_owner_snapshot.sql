-- Legacy reservations remain NULL until separately verified; never infer their historic beneficiary.
ALTER TABLE reservation_order ADD COLUMN owner_id BIGINT NULL;
