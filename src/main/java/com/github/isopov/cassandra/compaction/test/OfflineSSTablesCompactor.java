package com.github.isopov.cassandra.compaction.test;

import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.statements.schema.CreateTableStatement;
import org.apache.cassandra.db.SerializationHeader;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterator;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterators;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.io.sstable.Component;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.SSTable;
import org.apache.cassandra.io.sstable.SSTableTxnWriter;
import org.apache.cassandra.io.sstable.SequenceBasedSSTableId;
import org.apache.cassandra.io.sstable.format.SSTableFormat;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.util.File;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.schema.TableMetadataRef;
import org.apache.cassandra.service.ActiveRepairService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compacts all sstables of mydb.users from a directory into one sstable in a plain local java process, without a
 * Cassandra node: the sstables are opened directly, their partitions are merged in token order (rows of the same
 * partition from different sstables are merged together, as a major compaction does) and written into a new sstable.
 * Tombstones are kept as they are (nothing is purged, there is no gc_grace_seconds check).
 * Arguments: [input directory, default dummysstables] [output directory, default compactedsstables/mydb/users]
 * The output directory must be empty or not exist, it is named keyspace/table so that it can be used by sstableloader.
 */
public class OfflineSSTablesCompactor {

    static {
        // the same initialization as CQLSSTableWriter does for client (offline) usage
        CassandraRelevantProperties.FORCE_LOAD_LOCAL_KEYSPACES.setBoolean(true);
        DatabaseDescriptor.clientInitialization(false);
        if (DatabaseDescriptor.getPartitioner() == null) {
            DatabaseDescriptor.setPartitionerUnsafe(Murmur3Partitioner.instance);
        }
    }

    public static void main(String[] args) {
        File inputDir = new File(args.length > 0 ? args[0] : "dummysstables");
        File outputDir = new File(args.length > 1 ? args[1] : "compactedsstables/mydb/users");

        TableMetadata table = CreateTableStatement.parse(DummySSTablesCreator.SCHEMA, "mydb").build();
        TableMetadataRef ref = TableMetadataRef.forOfflineTools(table);

        List<SSTableReader> readers = new ArrayList<>();
        for (File file : inputDir.tryList(f -> f.name().endsWith("-" + Component.DATA.name))) {
            Descriptor parsed = Descriptor.fromFilename(file);
            // keyspace and table are taken from parent directories by the parser, use the ones of the schema instead
            Descriptor descriptor = new Descriptor(parsed.version, parsed.directory, table.keyspace, table.name, parsed.id, parsed.formatType);
            readers.add(SSTableReader.openForBatch(descriptor, SSTable.componentsFor(descriptor), ref));
        }
        if (readers.isEmpty()) {
            throw new IllegalArgumentException("No sstables found in " + inputDir.absolutePath());
        }

        outputDir.createDirectoriesIfNotExists();
        if (outputDir.tryList().length > 0) {
            throw new IllegalArgumentException("Output directory " + outputDir.absolutePath() + " is not empty");
        }
        Descriptor output = new Descriptor(outputDir, table.keyspace, table.name, new SequenceBasedSSTableId(1), SSTableFormat.Type.BIG);

        // like a real compaction: the number of partitions for the bloom filter is estimated from the cardinality
        // estimators of the input sstables, the serialization header is merged from their headers
        long keyCount = SSTableReader.getApproximateKeyCount(readers);
        SerializationHeader header = SerializationHeader.make(table, readers);
        long inputBytes = readers.stream().mapToLong(SSTableReader::onDiskLength).sum();
        System.out.printf("Compacting %d sstables (%d bytes, about %d partitions) from %s%n",
                readers.size(), inputBytes, keyCount, inputDir.absolutePath());

        long start = System.nanoTime();
        long partitions = 0;
        List<ISSTableScanner> scanners = new ArrayList<>();
        try {
            for (SSTableReader reader : readers) {
                scanners.add(reader.getScanner());
            }
            try (UnfilteredPartitionIterator merged = UnfilteredPartitionIterators.merge(scanners, UnfilteredPartitionIterators.MergeListener.NOOP);
                 SSTableTxnWriter writer = SSTableTxnWriter.create(ref, output, keyCount, ActiveRepairService.UNREPAIRED_SSTABLE,
                         ActiveRepairService.NO_PENDING_REPAIR, false, 0, header, Collections.emptySet())) {
                while (merged.hasNext()) {
                    try (UnfilteredRowIterator partition = merged.next()) {
                        if (writer.append(partition)) {
                            partitions++;
                        }
                    }
                }
                writer.finish(false);
            }
        } finally {
            // closing the merged iterator closes the scanners too, this is for failures before it was created
            scanners.forEach(ISSTableScanner::close);
            readers.forEach(reader -> reader.selfRef().release());
        }

        System.out.printf("Wrote %d partitions (%d bytes) into %s in %d ms%n",
                partitions, new File(output.filenameFor(Component.DATA)).length(), output.filenameFor(Component.DATA),
                (System.nanoTime() - start) / 1_000_000);
    }
}
