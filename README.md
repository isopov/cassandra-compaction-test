# cassandra-compaction-test

Experiments with how many sstables Cassandra has to read per query, depending on how the data is laid out on disk.

Prerequisites:

* JDK 11 for this project (the default `java` on `PATH`)
* python3 and git for [ccm](https://github.com/apache/cassandra-ccm/)

## 1. Create sstables

To create a local folder `dummysstables` with 139 1mb sstables of the `mydb.users` table run this
```
./mvnw clean compile exec:java -Dexec.mainClass=com.github.isopov.cassandra.compaction.test.DummySSTablesCreator
```

`exec:java` does not compile anything by itself, so always keep `compile` in the command, otherwise a stale class is run.

The creator adds new sstables to `dummysstables` if it already exists, so remove it before creating sstables again:
```
rm -rf dummysstables
```

## 2. Install ccm into a virtualenv

Following [INSTALL.md](https://github.com/apache/cassandra-ccm/blob/trunk/INSTALL.md), from the project root:
```
git clone https://github.com/apache/cassandra-ccm.git
python3 -m venv --prompt ccm .venv
source .venv/bin/activate
pip install -e ./cassandra-ccm
pip install setuptools cqlsh
```

* `setuptools` is needed on Python 3.12+, where `distutils` (still imported by ccm) was removed.
* `cqlsh` from PyPI is needed on Python 3.12+, because the cqlsh bundled with Cassandra 4.1 fails there with
  `No module named 'six.moves'`. It warns that it was built against 5.0, which is fine for the commands below.

Both `cassandra-ccm` and `.venv` are git-ignored. In every new terminal run `source .venv/bin/activate` before using `ccm`.

## 3. Create a local test cluster

ccm fails to start nodes without `JAVA_HOME` (`KeyError: 'JAVA_HOME'`), so if it is not set, point it to the default JDK
(in every terminal where `ccm` is used):
```
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
```

Create and start a single node cluster with the same Cassandra version as `cassandra-all` in `pom.xml` (the first run
downloads Cassandra from archive.apache.org into `~/.ccm/repository` and can take a while):
```
ccm create compaction -v 4.1.11 -n 1 -s
ccm status
```

The node listens on `127.0.0.1:9042`. `ccm create` also makes `compaction` the current ccm cluster, see `ccm list` and
`ccm switch <name>`. Another ccm cluster using `127.0.0.1` can't run at the same time.

## 4. Create the table with autocompaction disabled

The schema must be the same as in `DummySSTablesCreator`. `'enabled': 'false'` in the compaction options disables
autocompaction for the table persistently (it survives node restarts):
```
cqlsh 127.0.0.1 -e "
CREATE KEYSPACE mydb WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};
CREATE TABLE mydb.users (id uuid, name text, PRIMARY KEY (id))
    WITH compaction = {'class': 'SizeTieredCompactionStrategy', 'enabled': 'false'};"
```

Also disable it at runtime, just in case (this one does not survive a restart):
```
ccm node1 nodetool disableautocompaction mydb users
```

## 5. Upload the sstables

`nodetool import` adds the sstables to the table as they are (no streaming, no splitting). `--copy-data` keeps the
original files in `dummysstables`, the path must be absolute:
```
ccm node1 nodetool import -- --copy-data mydb users "$PWD/dummysstables"
```

Sstables written by `CQLSSTableWriter` of Cassandra 4.1 have empty bloom filters (`*-Filter.db` files are 16 bytes),
so every read has to look into the partition index of almost every sstable. To get normal bloom filters rewrite every
sstable one by one (this keeps the number of sstables, it is not a compaction):
```
ccm node1 nodetool upgradesstables -- -a mydb users
```

Check the result, `SSTable count` should be 139:
```
ccm node1 nodetool tablestats mydb.users
ccm node1 nodetool compactionstats
```

## 6. Count sstables read per query

`SSTablesPerReadReporter` takes some keys from `mydb.users`, reads them one by one with query tracing on and reports
for each of them how many sstables were read (the partition was found there), how many were only looked up in the
partition index (bloom filter false positive) and how many were skipped by the bloom filter, then prints histograms.
Arguments are the number of keys (default 20) and whether to print all trace events (default false):
```
./mvnw compile exec:java -Dexec.mainClass=com.github.isopov.cassandra.compaction.test.SSTablesPerReadReporter -Dexec.args="200 false"
```

## Cleanup

```
ccm stop               # stop the node, data is kept
ccm remove compaction  # stop and delete the cluster with all its data
```
