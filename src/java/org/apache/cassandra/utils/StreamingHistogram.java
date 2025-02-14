/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.utils;

import java.io.IOException;
import java.util.*;

import com.google.common.base.Objects;

import org.apache.cassandra.db.TypeSizes;
import org.apache.cassandra.io.ISerializer;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;

/**
 * Histogram that can be constructed from streaming of data.
 *
 * The algorithm is taken from following paper:
 * Yael Ben-Haim and Elad Tom-Tov, "A Streaming Parallel Decision Tree Algorithm" (2010)
 * http://jmlr.csail.mit.edu/papers/volume11/ben-haim10a/ben-haim10a.pdf
 */
public class StreamingHistogram
{
    public static final StreamingHistogramSerializer serializer = new StreamingHistogramSerializer();

    // TreeMap to hold bins of histogram.
    private final TreeMap<Double, Long> bin;
    private final int maxBinSize;

    private StreamingHistogram(int maxBinSize, Map<Double, Long> bin)
    {
        this.maxBinSize = maxBinSize;
        this.bin = new TreeMap<>(bin);
    }

    /**
     * Calculates estimated number of points in interval [-inf,b].
     *
     * @param b upper bound of a interval to calculate sum
     * @return estimated number of points in a interval [-inf,b].
     */
    public double sum(double b)
    {
        double sum = 0;
        // find the points pi, pnext which satisfy pi <= b < pnext
        Map.Entry<Double, Long> pnext = bin.higherEntry(b);
        if (pnext == null)
        {
            // if b is greater than any key in this histogram,
            // just count all appearance and return
            for (Long value : bin.values())
                sum += value;
        }
        else
        {
            Map.Entry<Double, Long> pi = bin.floorEntry(b);
            if (pi == null)
                return 0;
            // calculate estimated count mb for point b
            double weight = (b - pi.getKey()) / (pnext.getKey() - pi.getKey());
            double mb = pi.getValue() + (pnext.getValue() - pi.getValue()) * weight;
            sum += (pi.getValue() + mb) * weight / 2;

            sum += pi.getValue() / 2.0;
            for (Long value : bin.headMap(pi.getKey(), false).values())
                sum += value;
        }
        return sum;
    }

    public Map<Double, Long> getAsMap()
    {
        return Collections.unmodifiableMap(bin);
    }

    public static class StreamingHistogramBuilder
    {
        // TreeMap to hold bins of histogram.
        private final TreeMap<Double, Long> bin;

        // Keep a second, larger buffer to spool data in, before finalizing it into `bin`
        private final TreeMap<Double, Long> spool;

        // maximum bin size for this histogram
        private final int maxBinSize;

        // maximum size of the spool
        private final int maxSpoolSize;

        // voluntarily give up resolution for speed
        private final int roundSeconds;
        /**
         * Creates a new histogram with max bin size of maxBinSize
         * @param maxBinSize maximum number of bins this histogram can have
         */
        public StreamingHistogramBuilder(int maxBinSize, int maxSpoolSize, int roundSeconds)
        {
            this.maxBinSize = maxBinSize;
            this.maxSpoolSize = maxSpoolSize;
            this.roundSeconds = roundSeconds;
            bin = new TreeMap<>();
            spool = new TreeMap<>();
        }

        public StreamingHistogram build()
        {
            flushHistogram();
            return new StreamingHistogram(maxBinSize, bin);
        }
        /**
         * Adds new point p to this histogram.
         * @param p
         */
        public void update(double p)
        {
            update(p, 1);
        }

        /**
         * Adds new point p with value m to this histogram.
         * @param p
         * @param m
         */
        public void update(double p, long m)
        {
            double d = p % this.roundSeconds;
            if (d > 0)
                p = p + (this.roundSeconds - d);

            Long mi = spool.get(p);
            if (mi != null)
            {
                // we found the same p so increment that counter
                spool.put(p, mi + m);
            }
            else
            {
                spool.put(p, m);
            }
            if(spool.size() > maxSpoolSize)
                flushHistogram();
        }

        /**
         * Drain the temporary spool into the final bins
         */
        public void flushHistogram()
        {
            if(spool.size() > 0)
            {
                Long spoolValue;
                Long binValue;

                // Iterate over the spool, copying the value into the primary bin map
                // and compacting that map as necessary
                for (Map.Entry<Double, Long> entry : spool.entrySet())
                {
                    Double key = entry.getKey();
                    spoolValue = entry.getValue();
                    binValue = bin.get(key);

                    if (binValue != null)
                    {
                        binValue += spoolValue;
                        bin.put(key, binValue);
                    } else
                    {
                        bin.put(key, spoolValue);
                    }

                    // if bin size exceeds maximum bin size then trim down to max size
                    if (bin.size() > maxBinSize)
                    {
                        // find points p1, p2 which have smallest difference
                        Iterator<Double> keys = bin.keySet().iterator();
                        double p1 = keys.next();
                        double p2 = keys.next();
                        double smallestDiff = p2 - p1;
                        double q1 = p1, q2 = p2;
                        while (keys.hasNext()) {
                            p1 = p2;
                            p2 = keys.next();
                            double diff = p2 - p1;
                            if (diff < smallestDiff) {
                                smallestDiff = diff;
                                q1 = p1;
                                q2 = p2;
                            }
                        }
                        // merge those two
                        long k1 = bin.remove(q1);
                        long k2 = bin.remove(q2);
                        bin.put((q1 * k1 + q2 * k2) / (k1 + k2), k1 + k2);
                    }
                }
                spool.clear();
            }
        }

        /**
        * Merges given histogram with this histogram.
        *
        * @param other histogram to merge
        */
        public void merge(StreamingHistogram other)
        {
            if (other == null)
                return;

            flushHistogram();

            for (Map.Entry<Double, Long> entry : other.getAsMap().entrySet())
                update(entry.getKey(), entry.getValue());
        }
    }

    public static class StreamingHistogramSerializer implements ISerializer<StreamingHistogram>
    {
        public void serialize(StreamingHistogram histogram, DataOutputPlus out) throws IOException
        {
            out.writeInt(histogram.maxBinSize);
            Map<Double, Long> entries = histogram.getAsMap();
            out.writeInt(entries.size());
            for (Map.Entry<Double, Long> entry : entries.entrySet())
            {
                out.writeDouble(entry.getKey());
                out.writeLong(entry.getValue());
            }
        }

        public StreamingHistogram deserialize(DataInputPlus in) throws IOException
        {
            int maxBinSize = in.readInt();
            int size = in.readInt();
            Map<Double, Long> tmp = new HashMap<>(size);
            for (int i = 0; i < size; i++)
            {
                tmp.put(in.readDouble(), in.readLong());
            }

            return new StreamingHistogram(maxBinSize, tmp);
        }

        public long serializedSize(StreamingHistogram histogram)
        {
            long size = TypeSizes.sizeof(histogram.maxBinSize);
            Map<Double, Long> entries = histogram.getAsMap();
            size += TypeSizes.sizeof(entries.size());
            // size of entries = size * (8(double) + 8(long))
            size += entries.size() * (8L + 8L);
            return size;
        }
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;

        if (!(o instanceof StreamingHistogram))
            return false;

        StreamingHistogram that = (StreamingHistogram) o;
        return maxBinSize == that.maxBinSize
               && bin.equals(that.bin);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(bin.hashCode(), maxBinSize);
    }
}
