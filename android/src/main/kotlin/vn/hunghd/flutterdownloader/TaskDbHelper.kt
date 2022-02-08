package vn.hunghd.flutterdownloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TaskDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_ENTRIES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(SQL_DELETE_ENTRIES)
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    companion object {
        const val DATABASE_VERSION = 3
        const val DATABASE_NAME = "download_tasks.db"
        private var instance: TaskDbHelper? = null
        private val SQL_CREATE_ENTRIES = "CREATE TABLE " + TaskEntry.TABLE_NAME.toString() + " (" +
                TaskEntry.toString() + " INTEGER PRIMARY KEY," +
                TaskEntry.COLUMN_NAME_TASK_ID.toString() + " VARCHAR(256), " +
                TaskEntry.COLUMN_NAME_URL.toString() + " TEXT, " +
                TaskEntry.COLUMN_NAME_STATUS.toString() + " INTEGER DEFAULT 0, " +
                TaskEntry.COLUMN_NAME_PROGRESS.toString() + " INTEGER DEFAULT 0, " +
                TaskEntry.COLUMN_NAME_FILE_NAME.toString() + " TEXT, " +
                TaskEntry.COLUMN_NAME_SAVED_DIR.toString() + " TEXT, " +
                TaskEntry.COLUMN_NAME_HEADERS.toString() + " TEXT, " +
                TaskEntry.COLUMN_NAME_MIME_TYPE.toString() + " VARCHAR(128), " +
                TaskEntry.COLUMN_NAME_RESUMABLE.toString() + " TINYINT DEFAULT 0, " +
                TaskEntry.COLUMN_NAME_SHOW_NOTIFICATION.toString() + " TINYINT DEFAULT 0, " +
                TaskEntry.COLUMN_NAME_OPEN_FILE_FROM_NOTIFICATION.toString() + " TINYINT DEFAULT 0, " +
                TaskEntry.COLUMN_NAME_TIME_CREATED.toString() + " INTEGER DEFAULT 0, " +
                TaskEntry.COLUMN_SAVE_IN_PUBLIC_STORAGE.toString() + " TINYINT DEFAULT 0" + ")"
        private const val SQL_DELETE_ENTRIES = "DROP TABLE IF EXISTS " + TaskEntry.TABLE_NAME
        fun getInstance(ctx: Context): TaskDbHelper? {

            // Use the application context, which will ensure that you
            // don't accidentally leak an Activity's context.
            // See this article for more information: http://bit.ly/6LRzfx
            if (instance == null) {
                instance = TaskDbHelper(ctx.applicationContext)
            }
            return instance
        }
    }
}