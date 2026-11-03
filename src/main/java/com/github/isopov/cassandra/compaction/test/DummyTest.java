package com.github.isopov.cassandra.compaction.test;

import org.apache.cassandra.io.sstable.CQLSSTableWriter;
import org.apache.cassandra.io.util.File;

import java.io.IOException;
import java.util.UUID;

public class DummyTest {
    static void main() throws IOException {

        String schema = "CREATE KEYSPACE IF NOT EXISTS mydb WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}; " +
                "CREATE TABLE IF NOT EXISTS mydb.users (id uuid, name text, PRIMARY KEY (id));";

        String insertStmt = "INSERT INTO mydb.users (id, name) VALUES (?, ?);";

        File outputDir = new File("dummysstable");
        outputDir.createDirectoriesIfNotExists();


        CQLSSTableWriter writer = CQLSSTableWriter.builder()
                .inDirectory(outputDir)
                .forTable(schema)
                .using(insertStmt)
                .build();

        for (int i = 0; i < 10; i++) {
            writer.addRow(UUID.randomUUID(), "Alice" + i);
        }

        writer.close();
    }
}
