package com.cramtale.app.dict

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.cramtale.core.dict.Row
import com.cramtale.core.dict.Sql

/** core's [Sql] on Android's SQLite. */
class AndroidSql(path: String) : Sql {
    private val db = SQLiteDatabase.openOrCreateDatabase(path, null)
    private val statements = HashMap<String, SQLiteStatement>()

    private fun bind(st: SQLiteStatement, args: List<Any?>) {
        st.clearBindings()
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.bindNull(i + 1)
                is Int -> st.bindLong(i + 1, a.toLong())
                is Long -> st.bindLong(i + 1, a)
                is Boolean -> st.bindLong(i + 1, if (a) 1 else 0)
                else -> st.bindString(i + 1, a.toString())
            }
        }
    }

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) { db.execSQL(sql); return }
        val st = statements.getOrPut(sql) { db.compileStatement(sql) }
        bind(st, args)
        st.executeInsert()
    }

    override fun <T> query(sql: String, args: List<Any?>, row: (Row) -> T): List<T> {
        // rawQuery binds text; SQLite's column affinity turns '123' back into an integer for comparisons
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            val r = object : Row {
                override fun long(i: Int) = c.getLong(i)
                override fun int(i: Int) = c.getInt(i)
                override fun string(i: Int): String? = c.getString(i)
                override fun isNull(i: Int) = c.isNull(i)
            }
            val out = ArrayList<T>()
            while (c.moveToNext()) out += row(r)
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() {
        statements.values.forEach { it.close() }
        db.close()
    }
}
