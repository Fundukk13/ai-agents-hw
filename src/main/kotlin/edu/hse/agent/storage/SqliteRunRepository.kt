package edu.hse.agent.storage

import edu.hse.agent.models.RunState
import edu.hse.agent.serialization.json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SqliteRunRepository(
    directory: Path,
) : RunRepository {
    override val directory: Path = Files.createDirectories(directory).toRealPath()
    val database: Path = this.directory.resolve("agent.db")
    private val lock = RunFileLock(this.directory)

    init {
        Class.forName("org.sqlite.JDBC")
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                val version =
                    statement.executeQuery("PRAGMA user_version").use {
                        it.next()
                        it.getInt(1)
                    }
                require(version in 0..1) { "Unsupported database schema version: $version" }
            }
            transaction(connection) {
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS runs (id TEXT PRIMARY KEY, version INTEGER NOT NULL, phase TEXT NOT NULL, status TEXT NOT NULL, state_json TEXT NOT NULL)",
                    )
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS budgets (run_id TEXT PRIMARY KEY REFERENCES runs(id) ON DELETE CASCADE, limits_json TEXT NOT NULL, usage_json TEXT NOT NULL)",
                    )
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS tasks (run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE, id TEXT NOT NULL, position INTEGER NOT NULL, status TEXT NOT NULL, task_json TEXT NOT NULL, PRIMARY KEY (run_id, id))",
                    )
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS tool_calls (run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE, id TEXT NOT NULL, task_id TEXT, phase TEXT NOT NULL, status TEXT NOT NULL, record_json TEXT NOT NULL, PRIMARY KEY (run_id, id))",
                    )
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS messages (run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE, position INTEGER NOT NULL, message_json TEXT NOT NULL, PRIMARY KEY (run_id, position))",
                    )
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS decisions (run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE, position INTEGER NOT NULL, decision_json TEXT NOT NULL, PRIMARY KEY (run_id, position))",
                    )
                    statement.execute("PRAGMA user_version=1")
                }
            }
        }
    }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$database").also { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute("PRAGMA synchronous=FULL")
            }
        }

    private fun <T> transaction(
        connection: Connection,
        action: () -> T,
    ): T {
        connection.autoCommit = false
        try {
            val result = action()
            connection.commit()
            return result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    override fun save(state: RunState) = persist(state, false)

    private fun persist(
        state: RunState,
        onlyIfMissing: Boolean,
    ) {
        RunId.validate(state.runId)
        require(state.version == 1) { "Unsupported state version" }
        connection().use { connection ->
            transaction(connection) {
                val conflict =
                    if (onlyIfMissing) {
                        "DO NOTHING"
                    } else {
                        "DO UPDATE SET version=excluded.version, phase=excluded.phase, status=excluded.status, state_json=excluded.state_json"
                    }
                val count =
                    connection
                        .prepareStatement(
                            "INSERT INTO runs(id, version, phase, status, state_json) VALUES (?, ?, ?, ?, ?) ON CONFLICT(id) $conflict",
                        ).use { statement ->
                            statement.setString(1, state.runId)
                            statement.setInt(2, state.version)
                            statement.setString(3, state.phase.name)
                            statement.setString(4, state.status.name)
                            statement.setString(5, json.encodeToString(state))
                            statement.executeUpdate()
                        }
                if (count != 0) saveComponents(connection, state)
            }
        }
    }

    private fun saveComponents(
        connection: Connection,
        state: RunState,
    ) {
        listOf("budgets", "tasks", "tool_calls", "messages", "decisions").forEach { table ->
            connection.prepareStatement("DELETE FROM $table WHERE run_id=?").use { statement ->
                statement.setString(1, state.runId)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement("INSERT INTO budgets VALUES (?, ?, ?)").use { statement ->
            statement.setString(1, state.runId)
            statement.setString(2, json.encodeToString(state.budget.limits))
            statement.setString(3, json.encodeToString(state.budget.used))
            statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO tasks VALUES (?, ?, ?, ?, ?)").use { statement ->
            state.tasks.forEachIndexed { index, task ->
                statement.setString(1, state.runId)
                statement.setString(2, task.id)
                statement.setInt(3, index)
                statement.setString(4, task.status.name)
                statement.setString(5, json.encodeToString(task))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement("INSERT INTO tool_calls VALUES (?, ?, ?, ?, ?, ?)").use { statement ->
            state.calls.values.forEach { record ->
                statement.setString(1, state.runId)
                statement.setString(2, record.call.id)
                statement.setString(3, record.taskId)
                statement.setString(4, record.phase.name)
                statement.setString(5, record.status.name)
                statement.setString(6, json.encodeToString(record))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement("INSERT INTO messages VALUES (?, ?, ?)").use { statement ->
            state.messages.forEachIndexed { index, message ->
                statement.setString(1, state.runId)
                statement.setInt(2, index)
                statement.setString(3, json.encodeToString(message))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement("INSERT INTO decisions VALUES (?, ?, ?)").use { statement ->
            state.decisions.forEachIndexed { index, decision ->
                statement.setString(1, state.runId)
                statement.setInt(2, index)
                statement.setString(3, json.encodeToString(decision))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun snapshot(id: String): String? =
        connection().use { connection ->
            connection.prepareStatement("SELECT state_json FROM runs WHERE id=?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
        }

    override fun load(id: String): RunState {
        RunId.validate(id)
        val saved =
            snapshot(id) ?: run {
                require(Files.isRegularFile(directory.resolve("$id.json"))) { "Unknown run_id: $id" }
                persist(JsonRunRepository(directory).load(id), true)
                requireNotNull(snapshot(id))
            }
        return json
            .decodeFromString<RunState>(
                saved,
            ).also { require(it.version == 1 && it.runId == id) { "Unsupported or mismatched state" } }
    }

    override fun listIds(): List<String> {
        val saved =
            connection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id FROM runs ORDER BY id").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            }
        return (saved + JsonRunRepository(directory).listIds()).distinct().sorted()
    }

    override fun <T> locked(
        id: String,
        action: () -> T,
    ): T = lock.locked(id, action)
}
