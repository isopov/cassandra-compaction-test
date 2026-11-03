To create a local folder with 139 1mb sstables run this
```
./mvnw clean compile exec:java -Dexec.mainClass=com.github.isopov.cassandra.compaction.test.DummySSTablesCreator
```