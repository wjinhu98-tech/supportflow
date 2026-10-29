#!/bin/sh
# Runs once in mysql's official init directory, and explicitly as "grant" after migration.
# The subshell also makes sourcing by docker-entrypoint.sh safe on Windows bind mounts.
(
    set -eu
    mode="${1:-init}"
    case "$mode" in
        mysqld|--*) mode=init ;;
        init|grant) ;;
        *) printf '%s\n' 'Expected init or grant mode.' >&2; exit 1 ;;
    esac

    read_database_password() {
        file="/run/secrets/$1"
        [ -r "$file" ] || { printf '%s\n' "Missing secret file: $1" >&2; exit 1; }
        secret_value="$(cat "$file")"
        case "$secret_value" in
            ''|*[!A-Za-z0-9_-]*) printf '%s\n' "Invalid database secret format: $1" >&2; exit 1 ;;
        esac
        [ "${#secret_value}" -ge 24 ] && [ "${#secret_value}" -le 128 ] || {
            printf '%s\n' "Database secret length outside allowed range: $1" >&2; exit 1;
        }
        printf '%s' "$secret_value"
    }

    root_password="$(read_database_password lab_root_password)"
    umask 077
    # One known temporary file, never a wildcard or directory cleanup.
    client_file=/tmp/supportflow-lab-root-client.cnf
    trap 'rm -f /tmp/supportflow-lab-root-client.cnf' EXIT
    {
        printf '[client]\nuser=root\npassword=%s\n' "$root_password"
        if [ "$mode" = grant ]; then
            printf 'host=mysql\nport=3306\nprotocol=TCP\nssl-mode=DISABLED\nget-server-public-key\n'
        else
            printf 'protocol=SOCKET\n'
        fi
    } > "$client_file"
    unset root_password

    if [ "$mode" = init ]; then
        app_password="$(read_database_password lab_app_password)"
        migrator_password="$(read_database_password lab_migrator_password)"
        mysql --defaults-extra-file="$client_file" <<'SQL'
CREATE DATABASE IF NOT EXISTS `supportflow_lab`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
SQL
        # Password alphabet is validated before interpolation; credentials go to stdin, not argv.
        printf "CREATE USER IF NOT EXISTS 'lab_app'@'%%' IDENTIFIED BY '%s';\nCREATE USER IF NOT EXISTS 'lab_migrator'@'%%' IDENTIFIED BY '%s';\n" \
            "$app_password" "$migrator_password" | mysql --defaults-extra-file="$client_file"
        unset app_password migrator_password
        # partial_revokes is OFF in this dedicated server. Escape the schema underscore in DB grants.
        mysql --defaults-extra-file="$client_file" <<'SQL'
GRANT SELECT ON `supportflow\_lab`.* TO 'lab_app'@'%';
GRANT SELECT, INSERT, UPDATE, CREATE, INDEX, ALTER ON `supportflow\_lab`.* TO 'lab_migrator'@'%';
SQL
        printf '%s\n' 'Isolated lab database and roles initialized.'
    else
        # The migration job has completed. Do not grant schema-wide writes or merchant-auth writes.
        mysql --defaults-extra-file="$client_file" <<'SQL'
GRANT UPDATE (status, received_at) ON `supportflow_lab`.`support_order` TO 'lab_app'@'%';
GRANT INSERT ON `supportflow_lab`.`support_order_receipt_audit` TO 'lab_app'@'%';
GRANT INSERT ON `supportflow_lab`.`support_order_buyer_deletion` TO 'lab_app'@'%';
GRANT INSERT, UPDATE ON `supportflow_lab`.`support_after_sale` TO 'lab_app'@'%';
GRANT INSERT ON `supportflow_lab`.`support_after_sale_audit` TO 'lab_app'@'%';
GRANT INSERT, UPDATE ON `supportflow_lab`.`support_after_sale_execution` TO 'lab_app'@'%';
GRANT INSERT ON `supportflow_lab`.`support_simulated_refund` TO 'lab_app'@'%';
GRANT INSERT, UPDATE ON `supportflow_lab`.`support_chat_conversation` TO 'lab_app'@'%';
GRANT INSERT, UPDATE ON `supportflow_lab`.`support_chat_turn` TO 'lab_app'@'%';
GRANT INSERT, UPDATE ON `supportflow_lab`.`support_agent_run` TO 'lab_app'@'%';
GRANT INSERT, UPDATE ON `supportflow_lab`.`support_agent_step` TO 'lab_app'@'%';
GRANT INSERT ON `supportflow_lab`.`support_chat_message` TO 'lab_app'@'%';
GRANT INSERT, UPDATE ON `supportflow_lab`.`support_knowledge_article` TO 'lab_app'@'%';
GRANT INSERT ON `supportflow_lab`.`support_knowledge_revision` TO 'lab_app'@'%';
GRANT INSERT ON `supportflow_lab`.`support_knowledge_audit` TO 'lab_app'@'%';
GRANT INSERT ON `supportflow_lab`.`support_knowledge_import` TO 'lab_app'@'%';
GRANT INSERT ON `supportflow_lab`.`support_knowledge_vector` TO 'lab_app'@'%';
SQL
        printf '%s\n' 'Isolated lab application table and column grants applied.'
    fi
)
