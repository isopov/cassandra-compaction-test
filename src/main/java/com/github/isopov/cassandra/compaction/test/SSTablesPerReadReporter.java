package com.github.isopov.cassandra.compaction.test;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.TraceEvent;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads some partitions from mydb.users one by one (by partition key only) with query tracing on and reports,
 * for each of them, how many sstables Cassandra (4.1) touched, based on the trace events of BigTableReader:
 * <ul>
 *     <li>read - "Partition index with N entries found for sstable X" or "Key cache hit for sstable X":
 *     the partition was found and its data was read</li>
 *     <li>index - "Partition index lookup allows skipping sstable X": bloom filter said "maybe",
 *     the partition index was read, but the key is not there</li>
 *     <li>bloom - "Bloom filter allows skipping sstable X": skipped without any disk access</li>
 * </ul>
 * and, as a cross-check, "Merged data from memtables and N sstables" of SinglePartitionReadCommand.
 * Arguments: [number of partitions to query, default 20] [verbose: print all trace events, default false]
 */
public class SSTablesPerReadReporter {

    private static final Pattern READ = Pattern.compile("(?:Partition index with \\d+ entries found|Key cache hit) for sstable (\\d+)");
    private static final Pattern INDEX = Pattern.compile("Partition index lookup allows skipping sstable (\\d+)");
    private static final Pattern BLOOM = Pattern.compile("Bloom filter allows skipping sstable (\\d+)");
    private static final Pattern MERGED = Pattern.compile("Merged data from memtables and (\\d+) sstables");

    public static void main(String[] args) {
        int keysCount = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        boolean verbose = args.length > 1 && Boolean.parseBoolean(args[1]);

        try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
                .withLocalDatacenter("datacenter1")
                .build()) {

            List<UUID> keys = new ArrayList<>();
            for (Row row : session.execute(SimpleStatement.newInstance("SELECT DISTINCT id FROM mydb.users LIMIT " + keysCount))) {
                keys.add(row.getUuid("id"));
            }

            PreparedStatement select = session.prepare("SELECT * FROM mydb.users WHERE id = ?");
            Map<Integer, Integer> readHistogram = new TreeMap<>();
            Map<Integer, Integer> touchedHistogram = new TreeMap<>();

            for (UUID key : keys) {
                ResultSet rs = session.execute(select.bind(key).setTracing(true));
                int rows = rs.all().size();
                ExecutionInfo info = rs.getExecutionInfo();
                List<TraceEvent> events = info.getQueryTrace().getEvents();

                int read = 0, index = 0, bloom = 0, merged = -1;
                for (TraceEvent event : events) {
                    String activity = event.getActivity();
                    if (activity == null) {
                        continue;
                    }
                    if (READ.matcher(activity).find()) {
                        read++;
                    } else if (INDEX.matcher(activity).find()) {
                        index++;
                    } else if (BLOOM.matcher(activity).find()) {
                        bloom++;
                    } else {
                        Matcher matcher = MERGED.matcher(activity);
                        if (matcher.find()) {
                            merged = Integer.parseInt(matcher.group(1));
                        }
                    }
                }
                readHistogram.merge(read, 1, Integer::sum);
                touchedHistogram.merge(read + index, 1, Integer::sum);

                System.out.printf("%s rows=%d sstables read=%d (merged=%d) index-only=%d bloom-skipped=%d trace=%s%n",
                        key, rows, read, merged, index, bloom, info.getTracingId());
                if (verbose) {
                    for (TraceEvent event : events) {
                        System.out.println("    " + event.getActivity());
                    }
                }
            }

            System.out.println();
            printHistogram("sstables read (partition found) -> queries", readHistogram);
            printHistogram("sstables touched on disk (read + index-only) -> queries", touchedHistogram);
        }
    }

    private static void printHistogram(String title, Map<Integer, Integer> histogram) {
        System.out.println(title);
        histogram.forEach((sstables, queries) -> System.out.printf("%5d -> %d%n", sstables, queries));
    }
}
