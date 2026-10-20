-- Отметка «передача партии в Moodle идёт»: сетевые вызовы Moodle выполняются вне транзакции базы, а повторная
-- передача той же партии ждёт окончания первой.
ALTER TABLE enrolment_batches ADD COLUMN moodle_transfer_started_at TIMESTAMP WITH TIME ZONE;
