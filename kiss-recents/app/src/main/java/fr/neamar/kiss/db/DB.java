package fr.neamar.kiss.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Fresh-install schema only: this app has its own package ID and never upgrades from older KISS databases.
 */
class DB extends SQLiteOpenHelper {

    private final static String DB_NAME = "kiss.s3db";
    private final static int DB_VERSION = 1;

    DB(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        // `query` is a keyword so we escape it. See: https://www.sqlite.org/lang_keywords.html
        database.execSQL("CREATE TABLE history ( _id INTEGER PRIMARY KEY AUTOINCREMENT, \"query\" TEXT, record TEXT NOT NULL, timeStamp INTEGER DEFAULT 0 NOT NULL)");
        database.execSQL("CREATE TABLE custom_apps ( _id INTEGER PRIMARY KEY AUTOINCREMENT, custom_flags INTEGER DEFAULT 0, component_name TEXT NOT NULL UNIQUE, name TEXT NOT NULL DEFAULT '' )");
        database.execSQL("CREATE INDEX index_component ON custom_apps(component_name);");
        database.execSQL("CREATE TABLE custom_components ( _id INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, package TEXT NOT NULL, class TEXT NOT NULL)");
        database.execSQL("CREATE INDEX idx_custom_components_id ON custom_components(id);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        // Nothing to migrate: version 1 is the first schema of this app
    }
}
