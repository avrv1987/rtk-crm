<?php
define('CLI_SCRIPT', true);

require('/opt/bitnami/moodle/config.php');
require_once($CFG->dirroot . '/course/lib.php');
require_once($CFG->dirroot . '/user/lib.php');
require_once($CFG->dirroot . '/group/lib.php');
require_once($CFG->dirroot . '/webservice/lib.php');
require_once($CFG->libdir . '/completionlib.php');
require_once($CFG->dirroot . '/completion/criteria/completion_criteria_self.php');
require_once($CFG->dirroot . '/completion/completion_completion.php');

const CRM_SERVICE_SHORTNAME = 'crm_readonly';
const CRM_SERVICE_USERNAME = 'crm-ws';
const CRM_ROLE_SHORTNAME = 'crmwsreader';
const CRM_JURY_USERNAME = 'crm-jury';
const CRM_JURY_ROLE_SHORTNAME = 'crmjuryviewer';

$jurypassword = (string) getenv('MOODLE_JURY_PASSWORD');
if ($jurypassword === '') {
    fwrite(STDERR, "MOODLE_JURY_PASSWORD is required
");
    exit(1);
}

\core\session\manager::set_user(get_admin());

set_config('noemailever', 1);
set_config('enablewebservices', 1);
set_config('enablecompletion', 1);
$protocols = array_filter(explode(',', (string) get_config('core', 'webserviceprotocols')));
if (!in_array('rest', $protocols, true)) {
    $protocols[] = 'rest';
    set_config('webserviceprotocols', implode(',', $protocols));
}

function crm_demo_user(string $username, string $firstname, string $lastname): stdClass {
    global $CFG, $DB;
    $user = $DB->get_record('user', ['username' => $username, 'mnethostid' => $CFG->mnet_localhost_id]);
    if ($user) {
        return $user;
    }
    $id = user_create_user((object) [
        'username' => $username,
        'auth' => 'manual',
        'password' => random_string(24) . 'Aa1!',
        'firstname' => $firstname,
        'lastname' => $lastname,
        'email' => $username . '@example.test',
        'confirmed' => 1,
        'mnethostid' => $CFG->mnet_localhost_id,
    ]);
    return $DB->get_record('user', ['id' => $id], '*', MUST_EXIST);
}

function crm_demo_course(string $shortname, string $fullname, int $enablecompletion): stdClass {
    global $DB;
    $course = $DB->get_record('course', ['shortname' => $shortname]);
    if (!$course) {
        $course = create_course((object) [
            'category' => core_course_category::get_default()->id,
            'shortname' => $shortname,
            'fullname' => $fullname,
            'format' => 'topics',
            'numsections' => 1,
            'groupmode' => SEPARATEGROUPS,
            'enablecompletion' => $enablecompletion,
        ]);
    }
    return $course;
}

function crm_demo_group(stdClass $course, string $idnumber, string $name): int {
    $group = groups_get_group_by_idnumber($course->id, $idnumber);
    if ($group) {
        return (int) $group->id;
    }
    return (int) groups_create_group((object) ['courseid' => $course->id, 'idnumber' => $idnumber, 'name' => $name]);
}

function crm_demo_enrol(stdClass $course, stdClass $user, string $roleshortname, int $status = ENROL_USER_ACTIVE): void {
    global $DB;
    $instance = $DB->get_record('enrol', ['courseid' => $course->id, 'enrol' => 'manual'], '*', MUST_EXIST);
    $roleid = (int) $DB->get_field('role', 'id', ['shortname' => $roleshortname], MUST_EXIST);
    enrol_get_plugin('manual')->enrol_user($instance, $user->id, $roleid, 0, 0, $status);
}

$serviceuser = crm_demo_user(CRM_SERVICE_USERNAME, 'CRM', 'Web Service');

$roleid = (int) $DB->get_field('role', 'id', ['shortname' => CRM_ROLE_SHORTNAME]);
if (!$roleid) {
    $roleid = create_role('CRM: чтение агрегатов обучения', CRM_ROLE_SHORTNAME, 'Ограниченная роль сервиса CRM', '');
}
set_role_contextlevels($roleid, [CONTEXT_SYSTEM]);
$systemcontext = context_system::instance();
foreach ([
    'webservice/rest:use',
    'moodle/course:view',
    'moodle/course:enrolreview',
    'moodle/course:managegroups',
    'moodle/site:accessallgroups',
    'moodle/user:viewdetails',
    'report/completion:view',
] as $capability) {
    assign_capability($capability, CAP_ALLOW, $roleid, $systemcontext->id, true);
}
role_assign($roleid, $serviceuser->id, $systemcontext->id);
$systemcontext->mark_dirty();

$webservicemanager = new webservice();
$service = $DB->get_record('external_services', ['shortname' => CRM_SERVICE_SHORTNAME]);
if (!$service) {
    $serviceid = $webservicemanager->add_external_service((object) [
        'name' => 'CRM: чтение агрегатов обучения',
        'shortname' => CRM_SERVICE_SHORTNAME,
        'enabled' => 1,
        'restrictedusers' => 1,
        'downloadfiles' => 0,
        'uploadfiles' => 0,
        'requiredcapability' => '',
    ]);
    $service = $DB->get_record('external_services', ['id' => $serviceid], '*', MUST_EXIST);
}
foreach ([
    'core_course_get_courses_by_field',
    'core_enrol_get_enrolled_users',
    'core_group_get_course_groups',
    'core_completion_get_course_completion_status',
] as $function) {
    if (!$webservicemanager->service_function_exists($function, $service->id)) {
        $webservicemanager->add_external_function_to_service($function, $service->id);
    }
}
if (!$DB->record_exists('external_services_users', ['externalserviceid' => $service->id, 'userid' => $serviceuser->id])) {
    $webservicemanager->add_ws_authorised_user((object) ['externalserviceid' => $service->id, 'userid' => $serviceuser->id]);
}

$token = $DB->get_field('external_tokens', 'token', [
    'userid' => $serviceuser->id,
    'externalserviceid' => $service->id,
    'tokentype' => EXTERNAL_TOKEN_PERMANENT,
], IGNORE_MULTIPLE);
if (!$token) {
    $token = \core_external\util::generate_token(
        EXTERNAL_TOKEN_PERMANENT,
        $service,
        $serviceuser->id,
        $systemcontext,
        0,
        '',
        'CRM'
    );
}

$teacher = crm_demo_user('crm-demo-t01', 'Преподаватель', 'Демо');
$students = [];
foreach (range(1, 10) as $number) {
    $suffix = sprintf('%02d', $number);
    $students[$number] = crm_demo_user('crm-demo-s' . $suffix, 'Учащийся', 'Демо ' . $suffix);
}

$java = crm_demo_course('CRM-DEMO-JAVA', 'Демо: Java-разработчик', 1);
if (!$DB->record_exists('course_completion_criteria', ['course' => $java->id, 'criteriatype' => COMPLETION_CRITERIA_TYPE_SELF])) {
    $criterion = new completion_criteria_self();
    $criteriadata = (object) ['id' => $java->id, 'criteria_self' => 1];
    $criterion->update_config($criteriadata);
}
$javagroups = [
    crm_demo_group($java, 'crm-demo-java-1', 'Поток А1'),
    crm_demo_group($java, 'crm-demo-java-2', 'Поток А2'),
];
crm_demo_enrol($java, $teacher, 'editingteacher');
foreach ([1, 2, 3, 4, 5, 6] as $number) {
    crm_demo_enrol($java, $students[$number], 'student');
    groups_add_member($javagroups[$number <= 3 ? 0 : 1], $students[$number]->id);
}
crm_demo_enrol($java, $students[7], 'student', ENROL_USER_SUSPENDED);
groups_add_member($javagroups[0], $students[7]->id);
foreach ([1, 2, 4] as $number) {
    $completion = new completion_completion(['userid' => $students[$number]->id, 'course' => $java->id]);
    if (!$completion->is_complete()) {
        $completion->mark_complete();
    }
}

$data = crm_demo_course('CRM-DEMO-DATA', 'Демо: анализ данных', 0);
$datagroup = crm_demo_group($data, 'crm-demo-data-1', 'Поток Б1');
crm_demo_enrol($data, $teacher, 'editingteacher');
foreach ([7, 8, 9, 10] as $number) {
    crm_demo_enrol($data, $students[$number], 'student');
    groups_add_member($datagroup, $students[$number]->id);
}

$jury = crm_demo_user(CRM_JURY_USERNAME, 'Жюри', 'Демо');
update_internal_user_password($jury, $jurypassword);
$juryroleid = (int) $DB->get_field('role', 'id', ['shortname' => CRM_JURY_ROLE_SHORTNAME]);
if (!$juryroleid) {
    $juryroleid = create_role('Жюри: просмотр демо-курсов', CRM_JURY_ROLE_SHORTNAME,
        'Просмотр курсов, участников, групп и завершения без записи на курс и без права изменений', '');
}
set_role_contextlevels($juryroleid, [CONTEXT_COURSECAT]);
foreach ([
    'moodle/course:view',
    'moodle/course:viewparticipants',
    'moodle/course:enrolreview',
    'moodle/site:accessallgroups',
    'moodle/user:viewdetails',
    'report/completion:view',
] as $capability) {
    assign_capability($capability, CAP_ALLOW, $juryroleid, $systemcontext->id, true);
}
role_assign($juryroleid, $jury->id, context_coursecat::instance($java->category)->id);

purge_caches();

echo 'COURSE_IDS=' . $java->id . ',' . $data->id . PHP_EOL;
echo 'DEMO_JAVA_COURSE=' . $java->id . PHP_EOL;
echo 'DEMO_DATA_GROUP=' . $data->id . ':' . $datagroup . PHP_EOL;
echo 'TOKEN=' . $token . PHP_EOL;
