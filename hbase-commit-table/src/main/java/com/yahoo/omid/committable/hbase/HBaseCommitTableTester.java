/**
 * Copyright 2011-2015 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.omid.committable.hbase;

import static com.yahoo.omid.committable.hbase.HBaseCommitTable.COMMIT_TABLE_DEFAULT_NAME;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.yahoo.omid.committable.CommitTable;
import com.yahoo.omid.committable.hbase.HBaseCommitTable.BadRandomKeyGenerator;
import com.yahoo.omid.committable.hbase.HBaseCommitTable.BucketKeyGenerator;
import com.yahoo.omid.committable.hbase.HBaseCommitTable.FullRandomKeyGenerator;
import com.yahoo.omid.committable.hbase.HBaseCommitTable.KeyGenerator;
import com.yahoo.omid.committable.hbase.HBaseCommitTable.SeqKeyGenerator;

public class HBaseCommitTableTester {

    static class Config {

        @Parameter(names = "-fullRandomAlgo", description = "Full random algo")
        boolean fullRandomAlgo = false;

        @Parameter(names = "-badRandomAlgo", description = "The original algo")
        boolean badRandomAlgo = false;

        @Parameter(names = "-bucketAlgo", description = "Bucketing algorithm")
        boolean bucketingAlgo = false;

        @Parameter(names = "-seqAlgo", description = "Sequential algorithm")
        boolean seqAlgo = false;

        @Parameter(names = "-batchSize", description = "batch size")
        int batchSize = 10000;

        @Parameter(names = "-graphite", description = "graphite server to report to")
        String graphite = null;

        @ParametersDelegate
        HBaseLogin.Config loginFlags = new HBaseLogin.Config();
    }

    public static void main(String[] args) throws Exception {
        Config config = new Config();
        new JCommander(config, args);

        Configuration hbaseConfig = HBaseConfiguration.create();

        final KeyGenerator keygen;
        if (config.fullRandomAlgo) {
            keygen = new FullRandomKeyGenerator();
        } else if (config.badRandomAlgo) {
            keygen = new BadRandomKeyGenerator();
        } else if (config.bucketingAlgo) {
            keygen = new BucketKeyGenerator();
        } else if (config.seqAlgo) {
            keygen = new SeqKeyGenerator();
        } else {
            keygen = null;
            assert (false);
        }

        HBaseLogin.loginIfNeeded(config.loginFlags);

        CommitTable commitTable = new HBaseCommitTable(hbaseConfig, COMMIT_TABLE_DEFAULT_NAME, keygen);

        CommitTable.Writer writer = commitTable.getWriter().get();

        MetricRegistry metrics = new MetricRegistry();
        if (config.graphite != null) {
            String parts[] = config.graphite.split(":");
            String host = parts[0];
            Integer port = Integer.valueOf(parts[1]);

            final Graphite graphite = new Graphite(new InetSocketAddress(host, port));
            final GraphiteReporter reporter = GraphiteReporter.forRegistry(metrics)
                    .prefixedWith("omid-hbase." + keygen.getClass().getSimpleName())
                    .convertRatesTo(TimeUnit.SECONDS)
                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                    .filter(MetricFilter.ALL)
                    .build(graphite);
            reporter.start(10, TimeUnit.SECONDS);
        }
        final ConsoleReporter reporter = ConsoleReporter.forRegistry(metrics)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        reporter.start(10, TimeUnit.SECONDS);

        Timer flushTimer = metrics.timer("flush");
        Meter commitsMeter = metrics.meter("commits");
        int i = 0;
        long ts = 0;
        while (true) {
            writer.addCommittedTransaction(ts++, ts++);
            if (i++ == config.batchSize) {
                commitsMeter.mark(i);
                long start = System.nanoTime();
                writer.flush().get();
                flushTimer.update((System.nanoTime() - start), TimeUnit.NANOSECONDS);
                i = 0;
            }
        }
    }

}