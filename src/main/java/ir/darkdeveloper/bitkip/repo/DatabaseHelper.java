package ir.darkdeveloper.bitkip.repo;

import ir.darkdeveloper.bitkip.config.AppConfigs;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static ir.darkdeveloper.bitkip.config.AppConfigs.log;

public class DatabaseHelper {


    static final String QUEUE_DOWNLOAD_TABLE_NAME = "queue_download",
            DOWNLOADS_TABLE_NAME = "downloads",
            QUEUES_TABLE_NAME = "queues",
            SCHEDULE_TABLE_NAME = "schedules";

    static String COL_DOWNLOAD_ID = "download_id",
            COL_QUEUE_ID = "queue_id", COL_QUEUE_NAME = "queue_name";


    static Connection openConnection() throws SQLException {
        var path = AppConfigs.dataPath + "bitkip.db";
        var conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        conn.createStatement().execute("PRAGMA foreign_keys=ON;");
        return conn;
    }


    static void createTable(String sql) {
        try {
            var con = openConnection();
            var stmt = con.createStatement();
            stmt.executeUpdate(sql);
            stmt.close();
            con.close();
        } catch (SQLException e) {
            log.severe(e.getLocalizedMessage());
        }
    }

    static void executeUpdateSql(String sql, boolean ignoreStackTrace) {
        try (var conn = openConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            if (!ignoreStackTrace)
                log.severe(e.getLocalizedMessage());
        }
    }


}
