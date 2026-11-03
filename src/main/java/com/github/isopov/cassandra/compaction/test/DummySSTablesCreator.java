package com.github.isopov.cassandra.compaction.test;

import org.apache.cassandra.io.sstable.CQLSSTableWriter;
import org.apache.cassandra.io.util.File;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class DummySSTablesCreator {
    static void main() throws IOException {

        String schema = "CREATE TABLE IF NOT EXISTS mydb.users (id uuid, name text, PRIMARY KEY (id));";

        String insertStmt = "INSERT INTO mydb.users (id, name) VALUES (?, ?);";

        File outputDir = new File("dummysstables");
        outputDir.createDirectoriesIfNotExists();


        CQLSSTableWriter writer = CQLSSTableWriter.builder()
                .inDirectory(outputDir)
                .forTable(schema)
                .withMaxSSTableSizeInMiB(1)
                .using(insertStmt)
                .build();

        for (int i = 0; i < 1_000_000; i++) {
            writer.addRow(UUID.randomUUID(), randomString(100));
        }

        writer.close();
    }


    public static String randomString(int length) {
        var buf = new char[length];
        for (int idx = 0; idx < buf.length; ++idx)
            buf[idx] = alphanum.charAt(ThreadLocalRandom.current().nextInt(alphanum.length()));
        return new String(buf);
    }

    public static final String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static final String lower = upper.toLowerCase(Locale.ROOT);

    public static final String digits = "0123456789";

    public static final String alphanum = upper + lower + digits;


}



