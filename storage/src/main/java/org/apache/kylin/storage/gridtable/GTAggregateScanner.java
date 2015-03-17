package org.apache.kylin.storage.gridtable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.SortedMap;

import org.apache.kylin.metadata.filter.TupleFilter;
import org.apache.kylin.metadata.measure.MeasureAggregator;

import com.google.common.collect.Maps;

public class GTAggregateScanner implements IGTScanner {

    final GTInfo info;
    final BitSet dimensions;
    final BitSet metrics;
    final String[] metricsAggrFuncs;
    final GTRawScanner rawScanner;

    GTAggregateScanner(GTInfo info, IGTStore store, GTRecord pkStart, GTRecord pkEndExclusive, BitSet dimensions, BitSet metrics, String[] metricsAggrFuncs, TupleFilter filterPushDown) {
        if (dimensions.intersects(metrics))
            throw new IllegalStateException();
        if (metrics.cardinality() != metricsAggrFuncs.length)
            throw new IllegalStateException();

        this.info = info;
        this.dimensions = dimensions;
        this.metrics = metrics;
        this.metricsAggrFuncs = metricsAggrFuncs;
        
        BitSet columns = new BitSet();
        columns.or(dimensions);
        columns.or(metrics);
        this.rawScanner = new GTRawScanner(info, store, pkStart, pkEndExclusive, columns, filterPushDown);
    }

    @Override
    public int getScannedRowCount() {
        return rawScanner.getScannedRowCount();
    }

    @Override
    public int getScannedRowBlockCount() {
        return rawScanner.getScannedRowBlockCount();
    }

    @Override
    public void close() throws IOException {
        rawScanner.close();
    }

    @Override
    public Iterator<GTRecord> iterator() {
        AggregationCache aggrCache = new AggregationCache();
        for (GTRecord r : rawScanner) {
            aggrCache.aggregate(r);
        }
        return aggrCache.iterator();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    class AggregationCache {
        final SortedMap<GTRecord, MeasureAggregator[]> aggBufMap;

        public AggregationCache() {
            this.aggBufMap = Maps.newTreeMap();
        }

        void aggregate(GTRecord r) {
            r.maskForEqualHashComp = dimensions;
            MeasureAggregator[] aggrs = aggBufMap.get(r);
            if (aggrs == null) {
                aggrs = new MeasureAggregator[metricsAggrFuncs.length];
                for (int i = 0, col = -1; i < aggrs.length; i++) {
                    col = metrics.nextSetBit(col + 1);
                    aggrs[i] = info.codeSystem.newMetricsAggregator(metricsAggrFuncs[i], col);
                }
                aggBufMap.put(r.copy(dimensions), aggrs);
            }

            for (int i = 0, col = -1; i < aggrs.length; i++) {
                col = metrics.nextSetBit(col + 1);
                Object metrics = info.codeSystem.decodeColumnValue(col, r.cols[col].asBuffer());
                aggrs[i].aggregate(metrics);
            }
        }

        public Iterator<GTRecord> iterator() {
            return new Iterator<GTRecord>() {
                
                Iterator<Entry<GTRecord, MeasureAggregator[]>> it = aggBufMap.entrySet().iterator();
                ByteBuffer metricsBuf = ByteBuffer.allocate(info.maxRecordLength);
                GTRecord oneRecord = new GTRecord(info); // avoid instance creation

                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public GTRecord next() {
                    Entry<GTRecord, MeasureAggregator[]> entry = it.next();
                    
                    GTRecord dims = entry.getKey();
                    for (int i = dimensions.nextSetBit(0); i >= 0; i = dimensions.nextSetBit(i + 1)) {
                        oneRecord.cols[i].set(dims.cols[i]);
                    }
                    
                    metricsBuf.clear();
                    MeasureAggregator[] aggrs = entry.getValue();
                    for (int i = 0, col = -1; i < aggrs.length; i++) {
                        col = metrics.nextSetBit(col + 1);
                        int pos = metricsBuf.position();
                        info.codeSystem.encodeColumnValue(col, aggrs[i].getState(), metricsBuf);
                        oneRecord.cols[col].set(metricsBuf.array(), pos, metricsBuf.position() - pos);
                    }
                    
                    return oneRecord;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        public long getSize() {
            return aggBufMap.size();
        }

        // ============================================================================
        
        transient int rowMemBytes;
        static final int MEMORY_USAGE_CAP = 500 * 1024 * 1024; // 500 MB

        public void checkMemoryUsage() {
            // about memory calculation,
            // http://seniorjava.wordpress.com/2013/09/01/java-objects-memory-size-reference/
            if (rowMemBytes <= 0) {
                if (aggBufMap.size() > 0) {
                    rowMemBytes = 0;
                    MeasureAggregator[] measureAggregators = aggBufMap.get(aggBufMap.firstKey());
                    for (MeasureAggregator agg : measureAggregators) {
                        rowMemBytes += agg.getMemBytes();
                    }
                }
            }
            int size = aggBufMap.size();
            int memUsage = (40 + rowMemBytes) * size;
            if (memUsage > MEMORY_USAGE_CAP) {
                throw new RuntimeException("Kylin coprocess memory usage goes beyond cap, (40 + " + rowMemBytes + ") * " + size + " > " + MEMORY_USAGE_CAP + ". Abord coprocessor.");
            }
        }
    }

}
