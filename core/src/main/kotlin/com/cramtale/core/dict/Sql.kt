package com.cramtale.core.dict

/**
 * The little SQLite surface the dictionary needs, so the same code runs on Android
 * (android.database.sqlite) and on the JVM (sqlite-jdbc, for tests and the CLI).
 */
interface Sql {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun <T> query(sql: String, args: List<Any?> = emptyList(), row: (Row) -> T): List<T>
    fun <T> transaction(block: () -> T): T
    fun close()
}

interface Row {
    fun long(i: Int): Long
    fun int(i: Int): Int
    fun string(i: Int): String?
    fun isNull(i: Int): Boolean
}

/** JVM implementation over JDBC (needs org.xerial:sqlite-jdbc on the classpath). */
class JdbcSql(path: String) : Sql {
    private val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$path")
    private val statements = HashMap<String, java.sql.PreparedStatement>()

    private fun prepare(sql: String) = statements.getOrPut(sql) { conn.prepareStatement(sql) }

    private fun bind(st: java.sql.PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setNull(i + 1, java.sql.Types.NULL)
                is Int -> st.setInt(i + 1, a)
                is Long -> st.setLong(i + 1, a)
                is Boolean -> st.setInt(i + 1, if (a) 1 else 0)
                else -> st.setString(i + 1, a.toString())
            }
        }
    }

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty() && !sql.trimStart().startsWith("INSERT", ignoreCase = true)) {
            conn.createStatement().use { it.executeUpdate(sql) }
            return
        }
        val st = prepare(sql)
        bind(st, args)
        st.executeUpdate()
    }

    override fun <T> query(sql: String, args: List<Any?>, row: (Row) -> T): List<T> {
        val st = prepare(sql)
        bind(st, args)
        st.executeQuery().use { rs ->
            val r = object : Row {
                override fun long(i: Int) = rs.getLong(i + 1)
                override fun int(i: Int) = rs.getInt(i + 1)
                override fun string(i: Int): String? = rs.getString(i + 1)
                override fun isNull(i: Int): Boolean { rs.getObject(i + 1); return rs.wasNull() }
            }
            val out = ArrayList<T>()
            while (rs.next()) out += row(r)
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            return result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    override fun close() {
        statements.values.forEach { runCatching { it.close() } }
        conn.close()
    }
}
