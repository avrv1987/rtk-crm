-- Вид взаимодействия по умолчанию задаётся явно (2.5, Н-16): раньше им молча становился первый действующий вид по
-- порядку, и новый вид с порядком 0 менял вид всех новых карточек. Отметку получает тот вид, что был первым до сих пор.
ALTER TABLE interaction_kinds ADD COLUMN default_kind BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE interaction_kinds SET default_kind = TRUE
WHERE id = (SELECT id FROM interaction_kinds WHERE active = TRUE ORDER BY sort_order, name, id LIMIT 1);
