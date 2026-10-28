package com.example.bot.data.support

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Creates its own authenticated cluster, never adopts a server URL or an existing data directory.
 * Teardown addresses the captured child Process, not a PID file, database name or filesystem path.
 * No SQL DROP or recursive filesystem deletion: stopped data/logs are retained for inspection.
 * This isolates database sessions; it does not sandbox hostile code running as the same OS user.
 */
internal class NativeSupportPostgres(
    private val binaries: Path,
    private val shutdownSeconds: Long = 20,
) : AutoCloseable {
    val username = "clb_support_test"
    val password: String = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    lateinit var directory: Path
        private set
    private var process: Process? = null
    private var closed = false
    private lateinit var serverUrl: String
    private lateinit var systemIdentifier: String
    val isAlive: Boolean get() = process?.isAlive == true

    init {
        require(
            binaries.isAbsolute &&
                listOf("initdb", "postgres", "pg_controldata").all {
                    Files.isExecutable(binaries.resolve(it))
                },
        ) { "Native PostgreSQL requires an absolute directory containing initdb, postgres and pg_controldata" }
    }

    fun createDatabase(prefix: String): String {
        require(prefix in setOf("support_native", "support_current", "support_v057_drift"))
        check(!closed) { "Native PostgreSQL is closed" }
        if (process == null) start()
        val name = prefix + "_" + UUID.randomUUID().toString().replace("-", "")
        connection().use { connection ->
            verifyCluster(connection)
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return serverUrl.substringBeforeLast('/') + "/$name"
    }

    override fun close() {
        closed = true
        val child = process ?: return
        // Process retains the exact child identity; never look up a PID again through a mutable pathname.
        // SIGTERM requests smart shutdown: unexpected live sessions cause an observable timeout.
        child.destroy()
        check(child.waitFor(shutdownSeconds, TimeUnit.SECONDS)) {
            "Owned native PostgreSQL did not stop; retained data: $directory"
        }
        check(child.exitValue() == 0) { "Owned native PostgreSQL exited abnormally; retained data: $directory" }
    }

    private fun start() {
        directory =
            Files
                .createTempDirectory(
                    "clb-support-pg-",
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------"),
                    ),
                ).toRealPath()
        val passwordFile = directory.resolve("password")
        Files.writeString(passwordFile, password)
        Files.setPosixFilePermissions(passwordFile, PosixFilePermissions.fromString("rw-------"))
        try {
            runTool(
                "initdb",
                "-D",
                directory.resolve("data").toString(),
                "-U",
                username,
                "--auth=scram-sha-256",
                "--pwfile=$passwordFile",
                "--encoding=UTF8",
                "--no-locale",
            )
            systemIdentifier =
                runTool("pg_controldata", directory.resolve("data").toString())
                    .lineSequence()
                    .first { it.startsWith("Database system identifier:") }
                    .substringAfter(':')
                    .trim()
            val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            serverUrl = "jdbc:postgresql://127.0.0.1:$port/postgres?connectTimeout=2&socketTimeout=5"
            process =
                command(
                    "postgres",
                    "-D",
                    directory.resolve("data").toString(),
                    "-h",
                    "127.0.0.1",
                    "-p",
                    port.toString(),
                    "-k",
                    "",
                    "-c",
                    "log_statement=ddl",
                ).redirectErrorStream(true).redirectOutput(directory.resolve("postgres.log").toFile()).start()
            awaitReady()
        } catch (failure: Exception) {
            try {
                close()
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun awaitReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline && isAlive) {
            try {
                connection().use(::verifyCluster)
                return
            } catch (_: SQLException) {
                Thread.sleep(50)
            }
        }
        error("Owned native PostgreSQL failed to start; retained data: $directory")
    }

    private fun connection(): Connection = DriverManager.getConnection(serverUrl, username, password)

    private fun verifyCluster(connection: Connection) {
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT current_setting('data_directory'), system_identifier::text FROM pg_control_system()",
                ).use { rows ->
                    check(rows.next())
                    check(
                        rows.getString(1) == directory.resolve("data").toString() &&
                            rows.getString(2) == systemIdentifier,
                    ) {
                        "Refusing SQL against a different PostgreSQL cluster"
                    }
                    check(isAlive) { "Owned PostgreSQL process has exited" }
                }
        }
    }

    private fun command(
        tool: String,
        vararg arguments: String,
    ): ProcessBuilder =
        ProcessBuilder(listOf(binaries.resolve(tool).toString()) + arguments).apply {
            // Neither libpq environment nor ambient service configuration may select an external server.
            environment().keys.removeIf { it.startsWith("PG") }
            environment()["LC_ALL"] = "C"
        }

    private fun runTool(
        tool: String,
        vararg arguments: String,
    ): String {
        val output = directory.resolve("$tool.log")
        val child = command(tool, *arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroy()
            error("Native PostgreSQL $tool timed out; retained data: $directory")
        }
        check(child.exitValue() == 0) { "Native PostgreSQL $tool failed; retained data: $directory" }
        return Files.readString(output)
    }

    companion object {
        @JvmStatic
        fun requested(): Boolean =
            System.getenv("CLB_SUPPORT_CREATION_TEST_PG_BIN") != null ||
                System.getenv("CLB_SUPPORT_CREATION_TEST_PG_URL") != null

        fun configured(
            binaries: String? = System.getenv("CLB_SUPPORT_CREATION_TEST_PG_BIN"),
            externalUrl: String? = System.getenv("CLB_SUPPORT_CREATION_TEST_PG_URL"),
        ): NativeSupportPostgres {
            require(externalUrl == null) {
                "External PostgreSQL URLs are forbidden; use CLB_SUPPORT_CREATION_TEST_PG_BIN for an owned cluster"
            }
            require(
                !binaries.isNullOrBlank(),
            ) { "CLB_SUPPORT_CREATION_TEST_PG_BIN must name local PostgreSQL binaries" }
            return NativeSupportPostgres(Path.of(binaries))
        }
    }
}
