-- Явное уточнение курса и группы Moodle у потока (решение владельца 21.10). Пусто — курс берётся из сопоставлений
-- программы потока, как раньше. Задаёт менеджер учебного процесса или администратор; передача и перенос используют
-- уточнение, если оно задано.
ALTER TABLE enrolment_streams ADD COLUMN moodle_course_id BIGINT;
ALTER TABLE enrolment_streams ADD COLUMN moodle_group_id BIGINT;
ALTER TABLE enrolment_streams ADD CONSTRAINT enrolment_streams_moodle_check
    CHECK ((moodle_course_id IS NULL OR moodle_course_id > 0)
        AND (moodle_group_id IS NULL OR (moodle_group_id > 0 AND moodle_course_id IS NOT NULL)));
