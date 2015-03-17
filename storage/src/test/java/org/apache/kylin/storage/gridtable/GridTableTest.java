package org.apache.kylin.storage.gridtable;

import static org.junit.Assert.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.BitSet;

import org.apache.hadoop.io.LongWritable;
import org.apache.kylin.metadata.model.DataType;
import org.apache.kylin.storage.gridtable.GTInfo.Builder;
import org.apache.kylin.storage.gridtable.memstore.GTSimpleMemStore;
import org.junit.Test;

public class GridTableTest {

    @Test
    public void testBasics() throws IOException {
        GTInfo info = basicInfo();
        GTSimpleMemStore store = new GTSimpleMemStore(info);
        GridTable table = new GridTable(info, store);

        GTBuilder builder = rebuild(table);
        IGTScanner scanner = scan(table);
        assertEquals(builder.getWrittenRowBlockCount(), scanner.getScannedRowBlockCount());
        assertEquals(builder.getWrittenRowCount(), scanner.getScannedRowCount());
    }

    @Test
    public void testAdvanced() throws IOException {
        GTInfo info = advancedInfo();
        GTSimpleMemStore store = new GTSimpleMemStore(info);
        GridTable table = new GridTable(info, store);

        GTBuilder builder = rebuild(table);
        IGTScanner scanner = scan(table);
        assertEquals(builder.getWrittenRowBlockCount(), scanner.getScannedRowBlockCount());
        assertEquals(builder.getWrittenRowCount(), scanner.getScannedRowCount());
    }

    @Test
    public void testAggregate() throws IOException {
        GTInfo info = advancedInfo();
        GTSimpleMemStore store = new GTSimpleMemStore(info);
        GridTable table = new GridTable(info, store);

        GTBuilder builder = rebuild(table);
        IGTScanner scanner = scanAndAggregate(table);
        assertEquals(builder.getWrittenRowBlockCount(), scanner.getScannedRowBlockCount());
        assertEquals(builder.getWrittenRowCount(), scanner.getScannedRowCount());
    }
    
    @Test
    public void testAppend() throws IOException {
        GTInfo info = advancedInfo();
        GTSimpleMemStore store = new GTSimpleMemStore(info);
        GridTable table = new GridTable(info, store);

        rebuildViaAppend(table);
        IGTScanner scanner = scan(table);
        assertEquals(3, scanner.getScannedRowBlockCount());
        assertEquals(10, scanner.getScannedRowCount());
    }

    private IGTScanner scan(GridTable table) throws IOException {
        IGTScanner scanner = table.scan(null, null, null, null);
        for (GTRecord r : scanner) {
            System.out.println(r);
        }
        scanner.close();
        System.out.println("Scanned Row Block Count: " + scanner.getScannedRowBlockCount());
        System.out.println("Scanned Row Count: " + scanner.getScannedRowCount());
        return scanner;
    }

    private IGTScanner scanAndAggregate(GridTable table) throws IOException {
        IGTScanner scanner = table.scanAndAggregate(null, null, setOf(0, 2), setOf(3, 4), new String[] { "count", "sum" }, null);
        for (GTRecord r : scanner) {
            System.out.println(r);
        }
        scanner.close();
        System.out.println("Scanned Row Block Count: " + scanner.getScannedRowBlockCount());
        System.out.println("Scanned Row Count: " + scanner.getScannedRowCount());
        return scanner;
    }
    
    private GTBuilder rebuild(GridTable table) throws IOException {
        GTRecord r = new GTRecord(table.getInfo());
        GTBuilder builder = table.rebuild();

        builder.write(r.setValues("2015-01-14", "Yang", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-14", "Luke", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-15", "Xu", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-15", "Dong", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-15", "Jason", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-16", "Mahone", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-16", "Shaofeng", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-16", "Qianhao", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-16", "George", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-17", "Kejia", "Food", new LongWritable(10), new BigDecimal("10.5")));

        builder.close();
        System.out.println("Written Row Block Count: " + builder.getWrittenRowBlockCount());
        System.out.println("Written Row Count: " + builder.getWrittenRowCount());
        return builder;
    }
    
    private void rebuildViaAppend(GridTable table) throws IOException {
        GTRecord r = new GTRecord(table.getInfo());
        GTBuilder builder;
        
        builder = table.append();
        builder.write(r.setValues("2015-01-14", "Yang", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-14", "Luke", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-15", "Xu", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-15", "Dong", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.close();
        System.out.println("Written Row Block Count: " + builder.getWrittenRowBlockCount());
        System.out.println("Written Row Count: " + builder.getWrittenRowCount());
        
        builder = table.append();
        builder.write(r.setValues("2015-01-15", "Jason", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-16", "Mahone", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-16", "Shaofeng", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.close();
        System.out.println("Written Row Block Count: " + builder.getWrittenRowBlockCount());
        System.out.println("Written Row Count: " + builder.getWrittenRowCount());

        builder = table.append();
        builder.write(r.setValues("2015-01-16", "Qianhao", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.write(r.setValues("2015-01-16", "George", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.close();
        System.out.println("Written Row Block Count: " + builder.getWrittenRowBlockCount());
        System.out.println("Written Row Count: " + builder.getWrittenRowCount());
        
        builder = table.append();
        builder.write(r.setValues("2015-01-17", "Kejia", "Food", new LongWritable(10), new BigDecimal("10.5")));
        builder.close();
        System.out.println("Written Row Block Count: " + builder.getWrittenRowBlockCount());
        System.out.println("Written Row Count: " + builder.getWrittenRowCount());
    }

    private GTInfo basicInfo() {
        Builder builder = infoBuilder();
        GTInfo info = builder.build();
        return info;
    }

    private GTInfo advancedInfo() {
        Builder builder = infoBuilder();
        builder.enableColumnBlock(new BitSet[] { setOf(0, 1, 2), setOf(3, 4) });
        builder.enableRowBlock(4);
        GTInfo info = builder.build();
        return info;
    }

    private Builder infoBuilder() {
        Builder builder = GTInfo.builder();
        builder.setCodeSystem(new GTSampleCodeSystem());
        builder.setColumns( //
                DataType.getInstance("varchar"), //
                DataType.getInstance("varchar"), //
                DataType.getInstance("varchar"), //
                DataType.getInstance("bigint"), //
                DataType.getInstance("decimal") //
        );
        builder.setPrimaryKey(setOf(0));
        return builder;
    }

    private BitSet setOf(int... values) {
        BitSet set = new BitSet();
        for (int i : values)
            set.set(i);
        return set;
    }
}
